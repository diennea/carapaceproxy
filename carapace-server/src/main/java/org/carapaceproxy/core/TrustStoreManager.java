package org.carapaceproxy.core;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import lombok.Getter;
import org.carapaceproxy.utils.CertificatesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrustStoreManager {

    private static final Logger LOG = LoggerFactory.getLogger(TrustStoreManager.class);

    private final File basePath;
    private RuntimeServerConfiguration currentConfiguration;

    @Getter
    private Map<String, X509Certificate> certificateAuthorities = new HashMap<>();
    @Getter
    private TrustManagerFactory trustManagerFactory;

    public TrustStoreManager(RuntimeServerConfiguration currentConfiguration, HttpProxyServer parent) {
        this.currentConfiguration = currentConfiguration;
        this.basePath = parent.getBasePath();
        loadTrustStore();
    }

    private void loadTrustStore() {
        String trustStoreFile = currentConfiguration.getSslTrustStoreFile();
        String trustStorePassword = currentConfiguration.getSslTrustStorePassword();

        if (trustStoreFile == null) {
            return;
        }

        LOG.debug("loading truststore from {}", trustStoreFile);
        try {
            KeyStore truststore = loadKeyStoreFromFile(trustStoreFile, trustStorePassword, basePath);
            if (truststore != null) {
                trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(truststore);
                certificateAuthorities = CertificatesUtils.loadCaCerts(trustManagerFactory);
            }
        } catch (CertificateException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
            LOG.error("Cannot load truststore from {} : {} ", trustStoreFile, e.getMessage());
        }
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        if (newConfiguration == null) {
            return;
        }
        LOG.debug("Reload truststore");
        this.currentConfiguration = newConfiguration;
        certificateAuthorities.clear();
        loadTrustStore();
    }

    private SslContext buildClientSslContext(final TrustManagerFactory trustManager) throws SSLException {
        final SslContextBuilder builder = SslContextBuilder.forClient();
        if (trustManager != null) {
            builder.trustManager(trustManager);
        }
        builder.applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1
        ));
        return builder.build();
    }

    private TrustManagerFactory getTrustManagerFactory(final String caPath, final String password)
            throws GeneralSecurityException, IOException {
        final KeyStore trustStore = loadKeyStoreFromFile(caPath, password, basePath);
        final String algorithm = TrustManagerFactory.getDefaultAlgorithm();
        final TrustManagerFactory trustManager = TrustManagerFactory.getInstance(algorithm);
        trustManager.init(trustStore);
        return trustManager;
    }

    /**
     * Build a default client-side Netty SslContext using the global TrustStore (if configured)
     * and with ALPN configured for HTTP/2 support.
     *
     * @return the built SslContext
     * @throws SSLException if SSL material cannot be built
     */
    public SslContext buildDefaultClientSslContext() throws SSLException {
        return buildClientSslContext(getTrustManagerFactory());
    }

    /**
     * Build a client-side Netty SslContext using a backend-specific CA file.
     * ALPN will be configured for HTTP/2 support.
     *
     * @param caPath   path to the CA keystore file (relative to basePath allowed)
     * @param password optional password for the keystore (null treated as empty)
     * @return the built SslContext
     * @throws IOException if the CA file cannot be read
     */
    public SslContext buildClientSslContextForCaFile(String caPath, String password)
            throws GeneralSecurityException, IOException {
        return buildClientSslContext(getTrustManagerFactory(caPath, password));
    }

}
