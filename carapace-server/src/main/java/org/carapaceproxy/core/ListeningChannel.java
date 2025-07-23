package org.carapaceproxy.core;

import static org.carapaceproxy.utils.AlpnUtils.configureAlpnForServer;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.prometheus.client.Counter;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.KeyManagerFactory;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;
import org.carapaceproxy.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;

public class ListeningChannel {

    private static final Logger LOG = LoggerFactory.getLogger(ListeningChannel.class);

    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private final int localPort;
    private final NetworkListenerConfiguration config;
    private final Counter.Child totalRequests;
    private final Map<String, SslContext> sslContexts;
    private final File basePath;
    private final RuntimeServerConfiguration currentConfiguration;
    private final HttpProxyServer parent;
    private DisposableServer channel;

    public ListeningChannel(
            final File basePath,
            final RuntimeServerConfiguration currentConfiguration,
            final HttpProxyServer parent,
            final ConcurrentMap<String, SslContext> cachedSslContexts,
            final NetworkListenerConfiguration config
    ) throws ConfigurationNotValidException {
        this.localPort = config.port() + parent.getListenersOffsetPort();
        this.config = config;
        this.totalRequests = TOTAL_REQUESTS_PER_LISTENER_COUNTER.labels(config.host() + "_" + this.localPort);
        this.basePath = basePath;
        this.currentConfiguration = currentConfiguration;
        this.parent = parent;
        this.sslContexts = new HashMap<>(currentConfiguration.getCertificates().size());
        for (final SSLCertificateConfiguration certificate : currentConfiguration.getCertificates().values()) {
            final String certificateId = certificate.getId();
            final SslContext sslContext = cachedSslContexts.containsKey(certificateId)
                    ? cachedSslContexts.get(certificateId)
                    : bootSslContext(config, certificate);
            if (sslContext == null) {
                // certificate configuration has some problem, should fallback to default certificate (legacy behavior)
                continue;
            }
            sslContexts.put(certificateId, sslContext);
        }
    }

    private static KeyManagerFactory loadKeyFactory(final SSLCertificateConfiguration certificate, final KeyStore keystore) throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        final KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final KeyManagerFactory wrapperFactory = new OpenSslCachingX509KeyManagerFactory(keyFactory);
        wrapperFactory.init(keystore, certificate.getPassword().toCharArray());
        LOG.debug("Initialized KeyManagerFactory with algorithm: {}", wrapperFactory.getAlgorithm());
        return wrapperFactory;
    }

    public void disposeChannel() {
        this.channel.disposeNow(Duration.ofSeconds(10));
        FutureMono.from(this.config.group().close()).block(Duration.ofSeconds(10));
    }

    public void incRequests() {
        totalRequests.inc();
    }

    public void clear() {
        this.sslContexts.clear();
    }

    private SslContext bootSslContext(final NetworkListenerConfiguration listener, final SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        try {
            final EndpointKey hostPort = new EndpointKey(listener.host(), listener.port()).offsetPort(parent.getListenersOffsetPort());
            final KeyStore keystore = loadKeyStore(certificate, hostPort);
            if (keystore == null) {
                // certificate configuration has some problem, should fallback to default certificate (legacy behavior)
                return null;
            }
            final KeyManagerFactory keyFactory = loadKeyFactory(certificate, keystore);
            final SslContextBuilder sslContextBuilder = SslContextBuilder
                    .forServer(keyFactory)
                    .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                    .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                    .sslProvider(SslProvider.OPENSSL)
                    .protocols(listener.sslProtocols());

            configureAlpnForServer(sslContextBuilder, listener.protocols(), listener.host(), listener.port());

            final String sslCiphers = listener.sslCiphers();
            if (sslCiphers != null && !sslCiphers.isEmpty()) {
                LOG.debug("required sslCiphers {}", sslCiphers);
                final List<String> ciphers = Arrays.asList(sslCiphers.split(","));
                sslContextBuilder.ciphers(ciphers);
            }
            final SslContext sslContext = sslContextBuilder.build();
            final Certificate[] chain = readChainFromKeystore(keystore);
            if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                parent.getOcspStaplingManager().addCertificateForStapling(chain);
                Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                attr.set(chain[0]);
            }
            return sslContext;
        } catch (IOException | GeneralSecurityException err) {
            LOG.error("ERROR booting listener", err);
            throw new ConfigurationNotValidException(err);
        }
    }

    private KeyStore loadKeyStore(final SSLCertificateConfiguration certificate, final EndpointKey hostPort) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        try {
            // Try to find certificate data on db
            final byte[] keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
            final KeyStore keystore;
            if (keystoreContent == null) {
                if (StringUtils.isBlank(certificate.getFile())) {
                    LOG.warn("No certificate file or dynamic certificate data for certificate id {}", certificate.getId());
                    return null;
                }
                LOG.debug("Start SSL with certificate id {}, on listener {}:{} file={}", certificate.getId(), hostPort.host(), hostPort.port(), certificate.getFile());
                keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
            } else {
                LOG.debug("Start SSL with dynamic certificate id {}, on listener {}:{}", certificate.getId(), hostPort.host(), hostPort.port());
                keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
            }
            LOG.debug("Loaded keystore with type: {}, size: {}, aliases: {}", keystore.getType(), keystore.size(), Collections.list(keystore.aliases()));
            return keystore;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            LOG.error(
                    "ERROR loading keystore for certificate {id {}, hostname {}, file {}, mode {}}",
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.getFile(),
                    certificate.getMode()
            );
            throw e;
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

    public SslContext getDefaultSslContext() {
        return this.sslContexts.get(config.defaultCertificate());
    }

    public reactor.netty.tcp.SslProvider.Builder apply(reactor.netty.tcp.SslProvider.Builder parentBuilder) {
        for (final Map.Entry<String, SslContext> entry : this.sslContexts.entrySet()) {
            if ("*".equals(entry.getKey())) {
                continue;
            }
            final String key = entry.getKey();
            final SslContext sslContext = entry.getValue();
            parentBuilder.addSniMapping(key, spec -> {
                final reactor.netty.tcp.SslProvider.Builder builder = spec.sslContext(sslContext);
                if (isOcspEnabled()) {
                    builder.handlerConfigurator(new OcspSslHandler(sslContext, parent.getOcspStaplingManager()));
                }
            });
        }
        return parentBuilder;
    }

    public boolean isOcspEnabled() {
        return currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported();
    }
}
