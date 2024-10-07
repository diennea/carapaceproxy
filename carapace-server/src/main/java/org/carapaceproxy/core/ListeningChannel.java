package org.carapaceproxy.core;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.prometheus.client.Counter;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.KeyManagerFactory;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;

public class ListeningChannel implements io.netty.util.AsyncMapping<String, SslContext> {

    private static final Logger LOG = LoggerFactory.getLogger(ListeningChannel.class);

    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private final int localPort;
    private final NetworkListenerConfiguration config;
    private final Counter.Child totalRequests;
    private final Map<String, SslContext> listenerSslContexts = new HashMap<>();
    private final File basePath;
    private final RuntimeServerConfiguration currentConfiguration;
    private final HttpProxyServer parent;
    private final ConcurrentMap<String, SslContext> sslContexts;
    private DisposableServer channel;

    public ListeningChannel(final File basePath, final RuntimeServerConfiguration currentConfiguration, final HttpProxyServer parent, final ConcurrentMap<String, SslContext> sslContexts, NetworkListenerConfiguration config) {
        this.localPort = config.port() + parent.getListenersOffsetPort();
        this.config = config;
        this.totalRequests = TOTAL_REQUESTS_PER_LISTENER_COUNTER.labels(config.host() + "_" + this.localPort);
        this.basePath = basePath;
        this.currentConfiguration = currentConfiguration;
        this.parent = parent;
        this.sslContexts = sslContexts;
    }

    public void disposeChannel() {
        this.channel.disposeNow(Duration.ofSeconds(10));
        FutureMono.from(this.config.group().close()).block(Duration.ofSeconds(10));
    }

    public void incRequests() {
        totalRequests.inc();
    }

    public void clear() {
        this.listenerSslContexts.clear();
    }

    @Override
    public Future<SslContext> map(final String sniHostname, final Promise<SslContext> promise) {
        try {
            return promise.setSuccess(map(sniHostname));
        } catch (final Exception err) {
            LOG.error(err.getMessage(), err);
            return promise.setFailure(err);
        }
    }

    public SslContext map(final String sniHostname) throws ConfigurationNotValidException {
        try {
            final var key = config.host() + ":" + this.localPort + "+" + sniHostname;
            if (LOG.isDebugEnabled()) {
                LOG.debug("resolve SNI mapping {}, key: {}", sniHostname, key);
            }
            if (listenerSslContexts.containsKey(key)) {
                return listenerSslContexts.get(key);
            }
            if (!sslContexts.containsKey(key)) {
                var chosen = chooseCertificate(sniHostname);
                if (chosen == null) {
                    throw new ConfigurationNotValidException(
                            "Cannot find a certificate for snihostname " + sniHostname
                            + ", with default cert for listener as '" + config.defaultCertificate()
                            + "', available " + currentConfiguration.getCertificates().keySet());
                }
                final var sslContext = bootSslContext(config, chosen);
                sslContexts.put(key, sslContext);
                listenerSslContexts.put(key, sslContext);
            }
            return sslContexts.get(key);
        } catch (ConfigurationNotValidException err) {
            LOG.error("Error booting certificate for SNI hostname {}, on listener {}", sniHostname, config, err);
            throw err;
        }
    }

    private SSLCertificateConfiguration chooseCertificate(final String sniHostname) {
        return CertificatesUtils.chooseCertificate(currentConfiguration, sniHostname, config.defaultCertificate());
    }

    private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        final var hostPort = new EndpointKey(listener.host(), listener.port()).offsetPort(parent.getListenersOffsetPort());
        var sslCiphers = listener.sslCiphers();

        try {
            // Try to find certificate data on db
            var keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
            final KeyStore keystore;
            if (keystoreContent == null) {
                if (certificate.isDynamic()) { // fallback to default certificate
                    certificate = currentConfiguration.getCertificates().get(listener.defaultCertificate());
                    if (certificate == null) {
                        throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + hostPort.host() + ": no default certificate setup.");
                    }
                }
                LOG.debug("start SSL with certificate id {}, on listener {}:{} file={}", certificate.getId(), hostPort.host(), hostPort.port(), certificate.getFile());
                keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
            } else {
                LOG.debug("start SSL with dynamic certificate id {}, on listener {}:{}", certificate.getId(), hostPort.host(), hostPort.port());
                keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
            }
            KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
            keyFactory.init(keystore, certificate.getPassword().toCharArray());

            List<String> ciphers = null;
            if (sslCiphers != null && !sslCiphers.isEmpty()) {
                LOG.debug("required sslCiphers {}", sslCiphers);
                ciphers = Arrays.asList(sslCiphers.split(","));
            }
            var sslContext = SslContextBuilder
                    .forServer(keyFactory)
                    .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                    .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                    .sslProvider(SslProvider.OPENSSL)
                    .protocols(listener.sslProtocols())
                    .ciphers(ciphers).build();

            var chain = readChainFromKeystore(keystore);
            if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                parent.getOcspStaplingManager().addCertificateForStapling(chain);
                var attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                attr.set(chain[0]);
            }

            return sslContext;
        } catch (IOException | GeneralSecurityException err) {
            LOG.error("ERROR booting listener", err);
            throw new ConfigurationNotValidException(err);
        }
    }

    public NetworkListenerConfiguration getConfig() {
        return this.config;
    }

    public int getTotalRequests() {
        return (int) this.totalRequests.get();
    }

    public void setChannel(DisposableServer channel) {
        this.channel = channel;
    }

    public EndpointKey getHostPort() {
        return new EndpointKey(this.config.host(), this.localPort);
    }

    public int getChannelPort() {
        if (this.channel != null) {
            if (this.channel.address() instanceof InetSocketAddress address) {
                return address.getPort();
            }
            LOG.warn("Unexpected channel address {}", this.channel.address());
        }
        return -1;
    }

    public SslContext defaultSslContext() {
        try {
            return map(config.defaultCertificate());
        } catch (final ConfigurationNotValidException e) {
            throw new RuntimeException("Failed to load default SSL context", e);
        }
    }

    public void applySslContext(final String sniHostname, final reactor.netty.tcp.SslProvider.Builder sslContextBuilder) {
        if (sniHostname.charAt(0) == '*' && (sniHostname.length() < 3 || sniHostname.charAt(1) != '.')) {
            // skip, ReactorNetty won't accept it!
            return;
        }
        try {
            // #map should cache the certificate after the first search of the different SANs of the same certificate
            final SslContext sslContext = map(sniHostname);
            sslContextBuilder.addSniMapping(sniHostname, spec -> spec.sslContext(sslContext));
        } catch (final ConfigurationNotValidException e) {
            throw new RuntimeException(e);
        }
    }
}
