package org.carapaceproxy.core.ssl;

import static org.carapaceproxy.core.ssl.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.core.ssl.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.core.ssl.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AsyncMapping;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import javax.net.ssl.KeyManagerFactory;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.Listeners;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.tcp.SslProvider;

public final class SniMapper {
    private static final Logger LOG = LoggerFactory.getLogger(SniMapper.class);
    private final HttpProxyServer parent;
    private final RuntimeServerConfiguration runtimeConfiguration;
    private final NetworkListenerConfiguration listenerConfiguration;
    private final HostPort hostPort;
    private final ConcurrentMap<String, SslContext> sslContextsCache;

    public SniMapper(
            final HttpProxyServer parent,
            final RuntimeServerConfiguration runtimeConfiguration,
            final NetworkListenerConfiguration listenerConfiguration,
            final HostPort hostPort,
            final ConcurrentMap<String, SslContext> sslContextsCache
    ) {
        this.parent = parent;
        /*
         * todo:
         *  I don't think we actually need to store these data that should already be in the `parent`...
         *  sadly, this breaks the reload of the configuration after replacing the ConfigurationStore;
         *  one problem at a time though, this should be a different GitHub issue!
         */
        this.runtimeConfiguration = runtimeConfiguration;
        this.listenerConfiguration = listenerConfiguration;
        this.hostPort = hostPort;
        this.sslContextsCache = sslContextsCache;
    }

    public AsyncMapping<String, SslContext> sslContextAsyncMapping() {
        return this::computeContext;
    }

    private Future<SslContext> computeContext(final String sniHostname, final Promise<SslContext> promise) {
        try {
            return promise.setSuccess(computeContext(sniHostname));
        } catch (ConfigurationNotValidException e) {
            return promise.setFailure(e);
        }
    }

    public SslContext computeContext(final String sniHostname) throws ConfigurationNotValidException {
        try {
            final var key = CertificatesUtils.computeKey(hostPort, sniHostname);
            LOG.debug("resolve SNI mapping {}, key: {}", sniHostname, key);
            try {
                if (sslContextsCache.containsKey(key)) {
                    return sslContextsCache.get(key);
                }
                final var defaultCertificate = listenerConfiguration.getDefaultCertificate();
                var chosen = Listeners.chooseCertificate(runtimeConfiguration, sniHostname, defaultCertificate);
                if (chosen == null) {
                    throw new ConfigurationNotValidException(
                            "cannot find a certificate for snihostname " + sniHostname
                            + ", with default cert for listener as '" + defaultCertificate
                            + "', available " + runtimeConfiguration.getCertificates().keySet());
                }
                int port = listenerConfiguration.getPort() + parent.getListenersOffsetPort();
                try {
                    final byte[] keystoreContent;
                    if (chosen.isDynamic()) {
                        // Try to find certificate data on db
                        keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(chosen.getId());
                        if (keystoreContent == null) {
                            // fallback to default certificate
                            chosen = runtimeConfiguration.getCertificates().get(listenerConfiguration.getDefaultCertificate());
                            if (chosen == null) {
                                throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listenerConfiguration.getHost() + ": no default certificate setup.");
                            }
                        }
                    } else {
                        keystoreContent = null;
                    }
                    final KeyStore keystore;
                    if (chosen.isDynamic()) {
                        assert keystoreContent != null;
                        LOG.debug("start SSL with dynamic certificate id {}, on listener {}:{}", chosen.getId(), listenerConfiguration.getHost(), port);
                        keystore = loadKeyStoreData(keystoreContent, chosen.getPassword());
                    } else {
                        LOG.debug("start SSL with certificate id {}, on listener {}:{} file={}", chosen.getId(), listenerConfiguration.getHost(), port, chosen.getFile());
                        keystore = loadKeyStoreFromFile(chosen.getFile(), chosen.getPassword(), basePath());
                    }
                    KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
                    keyFactory.init(keystore, chosen.getPassword().toCharArray());

                    final var sslContext = SslContextBuilder
                            .forServer(keyFactory)
                            .enableOcsp(runtimeConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                            .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                            .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                            .protocols(listenerConfiguration.getSslProtocols())
                            .ciphers(getSslCiphers())
                            .build();

                    Certificate[] chain = readChainFromKeystore(keystore);
                    if (runtimeConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                        // ... so, in addition to `.enableOcsp(isEnableOcsp())` we need to store some additional info,
                        // that will be inspected on channel init by our SNI handler;
                        // see DisposableChannelListener#start
                        parent.getOcspStaplingManager().addCertificateForStapling(chain);
                        Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                        attr.set(chain[0]);
                    }

                    sslContextsCache.put(key, sslContext);
                    return sslContext;
                } catch (IOException | GeneralSecurityException err) {
                    LOG.error("ERROR booting listener", err);
                    throw new ConfigurationNotValidException(err);
                }
            } catch (RuntimeException err) {
                if (err.getCause() instanceof ConfigurationNotValidException) {
                    throw (ConfigurationNotValidException) err.getCause();
                }
                throw new ConfigurationNotValidException(err);
            }
        } catch (ConfigurationNotValidException err) {
            LOG.error("Error booting certificate for SNI hostname {}, on listener {}", sniHostname, listenerConfiguration);
            throw err;
        }
    }

    private File basePath() {
        return parent.getBasePath();
    }

    private List<String> getSslCiphers() {
        final var sslCiphers = listenerConfiguration.getSslCiphers();
        if (sslCiphers != null && !sslCiphers.isEmpty()) {
            LOG.debug("required sslCiphers {}", sslCiphers);
            return Arrays.asList(sslCiphers.split(","));
        }
        return null;
    }

    public Consumer<SslProvider.SslContextSpec> sslContextSpecConsumer() {
        return this::configureSpec;
    }

    private void configureSpec(final SslProvider.SslContextSpec sslContextSpec) {
        try {
            final var defaultSslContext = this.computeContext(listenerConfiguration.getDefaultCertificate());
            sslContextSpec.sslContext(defaultSslContext).setSniAsyncMappings(this.sslProviderAsyncMapping());
        } catch (ConfigurationNotValidException e) {
            throw new RuntimeException(e);
        }
    }

    public AsyncMapping<String, SslProvider> sslProviderAsyncMapping() {
        return this::computeProvider;
    }

    private Future<SslProvider> computeProvider(final String sniHostname, final Promise<SslProvider> promise) {
        try {
            return promise.setSuccess(SslProvider.builder().sslContext(computeContext(sniHostname)).build());
        } catch (ConfigurationNotValidException e) {
            return promise.setFailure(e);
        }
    }

}
