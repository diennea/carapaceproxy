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
import lombok.Data;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;

@Data
public class ListeningChannel implements io.netty.util.AsyncMapping<String, SslContext> {

    private static final Logger LOG = LoggerFactory.getLogger(ListeningChannel.class);

    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private final EndpointKey hostPort;
    private final NetworkListenerConfiguration config;
    private final Counter.Child totalRequests;
    private final Map<String, SslContext> listenerSslContexts = new HashMap<>();
    private final File basePath;
    private final RuntimeServerConfiguration currentConfiguration;
    private final HttpProxyServer parent;
    private final ConcurrentMap<String, SslContext> sslContexts;
    private DisposableServer channel;

    public ListeningChannel(final File basePath, final RuntimeServerConfiguration currentConfiguration, final HttpProxyServer parent, final ConcurrentMap<String, SslContext> sslContexts, EndpointKey hostPort, NetworkListenerConfiguration config) {
        this.hostPort = hostPort;
        this.config = config;
        this.totalRequests = TOTAL_REQUESTS_PER_LISTENER_COUNTER.labels(hostPort.host() + "_" + hostPort.port());
        this.basePath = basePath;
        this.currentConfiguration = currentConfiguration;
        this.parent = parent;
        this.sslContexts = sslContexts;
    }

    public int getLocalPort() {
        return ((InetSocketAddress) this.channel.address()).getPort();
    }

    public void disposeChannel() {
        this.channel.disposeNow(Duration.ofSeconds(10));
        FutureMono.from(this.config.getGroup().close()).block(Duration.ofSeconds(10));
    }

    public void incRequests() {
        totalRequests.inc();
    }

    public void clear() {
        this.listenerSslContexts.clear();
    }

    @Override
    public Future<SslContext> map(String sniHostname, Promise<SslContext> promise) {
        try {
            var key = config.getHost() + ":" + hostPort.port() + "+" + sniHostname;
            if (LOG.isDebugEnabled()) {
                LOG.debug("resolve SNI mapping {}, key: {}", sniHostname, key);
            }
            try {
                var sslContext = listenerSslContexts.get(key);
                if (sslContext != null) {
                    return promise.setSuccess(sslContext);
                }

                sslContext = sslContexts.computeIfAbsent(key, (k) -> {
                    try {
                        var chosen = chooseCertificate(sniHostname);
                        if (chosen == null) {
                            throw new ConfigurationNotValidException("cannot find a certificate for snihostname " + sniHostname
                                                                     + ", with default cert for listener as '" + config.getDefaultCertificate()
                                                                     + "', available " + currentConfiguration.getCertificates().keySet());
                        }
                        return bootSslContext(config, chosen);
                    } catch (ConfigurationNotValidException ex) {
                        throw new RuntimeException(ex);
                    }
                });
                listenerSslContexts.put(key, sslContext);

                return promise.setSuccess(sslContext);
            } catch (RuntimeException err) {
                if (err.getCause() instanceof ConfigurationNotValidException) {
                    throw (ConfigurationNotValidException) err.getCause();
                } else {
                    throw new ConfigurationNotValidException(err);
                }
            }
        } catch (ConfigurationNotValidException err) {
            LOG.error("Error booting certificate for SNI hostname {}, on listener {}", sniHostname, config, err);
            return promise.setFailure(err);
        }
    }

    private SSLCertificateConfiguration chooseCertificate(final String sniHostname) {
        return CertificatesUtils.chooseCertificate(currentConfiguration, sniHostname, config.getDefaultCertificate());
    }

    private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        var port = listener.getPort() + parent.getListenersOffsetPort();
        var sslCiphers = listener.getSslCiphers();

        try {
            // Try to find certificate data on db
            var keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
            final KeyStore keystore;
            if (keystoreContent == null) {
                if (certificate.isDynamic()) { // fallback to default certificate
                    certificate = currentConfiguration.getCertificates().get(listener.getDefaultCertificate());
                    if (certificate == null) {
                        throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listener.getHost() + ": no default certificate setup.");
                    }
                }
                LOG.debug("start SSL with certificate id {}, on listener {}:{} file={}", certificate.getId(), listener.getHost(), port, certificate.getFile());
                keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
            } else {
                LOG.debug("start SSL with dynamic certificate id {}, on listener {}:{}", certificate.getId(), listener.getHost(), port);
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
                    .protocols(listener.getSslProtocols())
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
}
