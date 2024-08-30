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
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.Listeners;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.tcp.SslProvider;

public final class SniMapper implements AsyncMapping<String, SslProvider> {
    private static final Logger LOG = LoggerFactory.getLogger(SniMapper.class);
    private final HttpProxyServer parent;
    private final RuntimeServerConfiguration runtimeConfiguration;
    private final NetworkListenerConfiguration listenerConfiguration;
    private final HostPort hostPort;

    public SniMapper(
            HttpProxyServer parent,
            RuntimeServerConfiguration runtimeConfiguration,
            NetworkListenerConfiguration listenerConfiguration,
            HostPort hostPort
    ) {
        this.parent = parent;
        this.runtimeConfiguration = runtimeConfiguration;
        this.listenerConfiguration = listenerConfiguration;
        this.hostPort = hostPort;
    }

    private boolean isEnableOcsp() {
        return runtimeConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported();
    }

    @Nullable
    private SSLCertificateConfiguration getDefaultSslConfiguration() {
        return runtimeConfiguration.getCertificates().get(getDefaultCertificate());
    }

    private KeyStore getKeyStore(final HostPort hostPort, final SSLCertificateConfiguration sslConfiguration) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, ConfigurationNotValidException, UnrecoverableKeyException {
        final var keyStoreContent = getCertificateForDomain(sslConfiguration);
        final KeyStore keyStore;
        if (keyStoreContent != null) {
            LOG.debug("start SSL with dynamic certificate id {}, on listener {}", sslConfiguration.getId(), hostPort);
            keyStore = loadKeyStoreData(keyStoreContent, sslConfiguration.getPassword());
        } else {
            LOG.debug("start SSL with certificate id {}, on listener {} file={}", sslConfiguration.getId(), hostPort, sslConfiguration.getFile());
            keyStore = loadKeyStoreFromFile(sslConfiguration.getFile(), sslConfiguration.getPassword(), basePath());
        }
        return keyStore;
    }

    private static KeyManagerFactory getKeyFactory(final KeyStore keyStore, final SSLCertificateConfiguration defaultSslConfiguration) throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        final var keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final var keyFactoryInstance = new OpenSslCachingX509KeyManagerFactory(keyFactory);
        keyFactoryInstance.init(keyStore, defaultSslConfiguration.getPassword().toCharArray());
        return keyFactoryInstance;
    }

    private List<String> getSslCiphers() {
        final var sslCiphers = listenerConfiguration.getSslCiphers();
        if (sslCiphers != null && !sslCiphers.isEmpty()) {
            LOG.debug("required sslCiphers {}", sslCiphers);
            return Arrays.asList(sslCiphers.split(","));
        }
        return null;
    }

    private String getDefaultCertificate() {
        return listenerConfiguration.getDefaultCertificate();
    }

    private byte[] getCertificateForDomain(final SSLCertificateConfiguration sslConfiguration) {
        return parent.getDynamicCertificatesManager().getCertificateForDomain(sslConfiguration.getId());
    }

    @Override
    public Future<SslProvider> map(final String sniHostname, final Promise<SslProvider> promise) {
        try {
            final var key = computeKey(sniHostname);
            LOG.debug("resolve SNI mapping {}, key: {}", sniHostname, key);
            try {
                final var defaultCertificate = listenerConfiguration.getDefaultCertificate();
                var chosen = Listeners.chooseCertificate(runtimeConfiguration, sniHostname, defaultCertificate);
                if (chosen == null) {
                    throw new ConfigurationNotValidException("cannot find a certificate for snihostname " + sniHostname
                                                             + ", with default cert for listener as '" + defaultCertificate
                                                             + "', available " + runtimeConfiguration.getCertificates().keySet());
                }
                int port = listenerConfiguration.getPort() + parent.getListenersOffsetPort();
                try {
                    // Try to find certificate data on db
                    byte[] keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(chosen.getId());
                    final KeyStore keystore;
                    if (keystoreContent != null) {
                        LOG.debug("start SSL with dynamic certificate id {}, on listener {}:{}", chosen.getId(), listenerConfiguration.getHost(), port);
                        keystore = loadKeyStoreData(keystoreContent, chosen.getPassword());
                    } else {
                        if (chosen.isDynamic()) { // fallback to default certificate
                            chosen = runtimeConfiguration.getCertificates().get(listenerConfiguration.getDefaultCertificate());
                            if (chosen == null) {
                                return promise.setFailure(new ConfigurationNotValidException("Unable to boot SSL context for listener " + listenerConfiguration.getHost() + ": no default certificate setup."));
                            }
                        }
                        LOG.debug("start SSL with certificate id {}, on listener {}:{} file={}", chosen.getId(), listenerConfiguration.getHost(), port, chosen.getFile());
                        keystore = loadKeyStoreFromFile(chosen.getFile(), chosen.getPassword(), basePath());
                    }
                    KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
                    keyFactory.init(keystore, chosen.getPassword().toCharArray());

                    SslContext sslContext = SslContextBuilder
                            .forServer(keyFactory)
                            .enableOcsp(isEnableOcsp())
                            .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                            .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                            .protocols(listenerConfiguration.getSslProtocols())
                            .ciphers(getSslCiphers())
                            .build();

                    Certificate[] chain = readChainFromKeystore(keystore);
                    if (runtimeConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                        parent.getOcspStaplingManager().addCertificateForStapling(chain);
                        Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                        attr.set(chain[0]);
                    }

                    return promise.setSuccess(SslProvider.builder().sslContext(sslContext).build());
                } catch (IOException | GeneralSecurityException err) {
                    LOG.error("ERROR booting listener", err);
                    return promise.setFailure(new ConfigurationNotValidException(err));
                }
            } catch (RuntimeException err) {
                if (err.getCause() instanceof ConfigurationNotValidException) {
                    throw (ConfigurationNotValidException) err.getCause();
                }
                throw new ConfigurationNotValidException(err);
            }
        } catch (ConfigurationNotValidException err) {
            LOG.error("Error booting certificate for SNI hostname {}, on listener {}", sniHostname, listenerConfiguration);
            return promise.setFailure(err);
        }
    }

    private File basePath() {
        return parent.getBasePath();
    }

    private String computeKey(final String sniHostname) {
        return listenerConfiguration.getHost() + ":" + hostPort.port() + "+" + sniHostname;
    }

    public HttpProxyServer parent() {
        return parent;
    }

    public RuntimeServerConfiguration runtimeConfiguration() {
        return runtimeConfiguration;
    }

    public NetworkListenerConfiguration listenerConfiguration() {
        return listenerConfiguration;
    }

    public HostPort hostPort() {
        return hostPort;
    }

}
