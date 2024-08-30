package org.carapaceproxy.core.ssl;

import static org.carapaceproxy.core.ssl.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.core.ssl.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.core.ssl.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
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

public final class SslContextConfigurator implements Consumer<SslProvider.SslContextSpec> {
    private static final Logger LOG = LoggerFactory.getLogger(SslContextConfigurator.class);

    private final HttpProxyServer parent;
    private final RuntimeServerConfiguration runtimeConfiguration;
    private final NetworkListenerConfiguration listenerConfiguration;
    private final HostPort hostPort;
    private final ConcurrentMap<String, SslContext> sslContextsCache;

    public SslContextConfigurator(
            final HttpProxyServer parent,
            final NetworkListenerConfiguration listenerConfiguration,
            final HostPort hostPort,
            final ConcurrentMap<String, SslContext> sslContextsCache
    ) {
        this.parent = parent;
        this.runtimeConfiguration = parent.getCurrentConfiguration();
        this.listenerConfiguration = listenerConfiguration;
        this.hostPort = hostPort;
        this.sslContextsCache = sslContextsCache;
    }

    @Override
    public void accept(final SslProvider.SslContextSpec sslContextSpec) {
        if (!listenerConfiguration.isSsl()) {
            // We do NOT want to alter the SslContextSpec if SSL is not enabled in our configurations
            return;
        }
        final SslContext sslContext;
        try {
            final var defaultSslConfiguration = getDefaultSslConfiguration();
            if (defaultSslConfiguration == null) {
                throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listenerConfiguration.getHost() + ": no default certificate setup.");
            }
            final var keyStore = getKeyStore(hostPort, defaultSslConfiguration);
            // todo compute key and store into cache
            sslContext = SslContextBuilder
                    .forServer(getKeyFactory(keyStore, defaultSslConfiguration))
                    .enableOcsp(isEnableOcsp())
                    .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                    .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                    .protocols(listenerConfiguration.getSslProtocols())
                    .ciphers(getSslCiphers())
                    .build();
            final var chain = readChainFromKeystore(keyStore);
            if (isEnableOcsp() && chain.length > 0) {
                parent.getOcspStaplingManager().addCertificateForStapling(chain);
                Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                attr.set(chain[0]);
                // todo i'm not sure if `.enableOcsp(isEnableOcsp())` and this part are enough,
                //  or if I should plug a `SniHandler` into the channel pipeline like we did in `Listeners`
            }
        } catch (final ConfigurationNotValidException | IOException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
        sslContextSpec.sslContext(sslContext).setSniAsyncMappings(new SniMapper(parent, runtimeConfiguration, listenerConfiguration, hostPort));
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
            keyStore = loadKeyStoreFromFile(sslConfiguration.getFile(), sslConfiguration.getPassword(), getBasePath());
        }
        return keyStore;
    }

    private static KeyManagerFactory getKeyFactory(final KeyStore keyStore, final SSLCertificateConfiguration defaultSslConfiguration) throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        final var keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final var keyFactoryInstance = new OpenSslCachingX509KeyManagerFactory(keyFactory);
        keyFactoryInstance.init(keyStore, defaultSslConfiguration.getPassword().toCharArray());
        return keyFactoryInstance;
    }

    private boolean isEnableOcsp() {
        return runtimeConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported();
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

    private File getBasePath() {
        return parent.getBasePath();
    }
}
