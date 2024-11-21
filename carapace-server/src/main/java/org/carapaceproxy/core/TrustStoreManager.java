package org.carapaceproxy.core;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.TrustManagerFactory;
import lombok.Getter;
import org.carapaceproxy.utils.CertificatesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrustStoreManager {

    private static final Logger LOG = LoggerFactory.getLogger(TrustStoreManager.class);

    @Getter
    private Map<String, X509Certificate> certificateAuthorities = new HashMap<>();
    @Getter
    private TrustManagerFactory trustManagerFactory;

    private RuntimeServerConfiguration currentConfiguration;
    private final File basePath;

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

}
