package org.carapaceproxy.core;

import lombok.Getter;
import org.carapaceproxy.utils.CertificatesUtils;

import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;

public class TrustStoreManager {

    private static final Logger LOG = Logger.getLogger(TrustStoreManager.class.getName());

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

        if(trustStoreFile == null) {
            return;
        }

        LOG.log(Level.FINE, "loading truststore from {0}", trustStoreFile);
        try {
            KeyStore truststore = loadKeyStoreFromFile(trustStoreFile, trustStorePassword, basePath);
            if (truststore != null) {
                trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(truststore);
                certificateAuthorities = CertificatesUtils.loadCaCerts(trustManagerFactory);
            }
        } catch (CertificateException | NoSuchAlgorithmException | KeyStoreException | IOException e) {
            LOG.log(Level.SEVERE, "Cannot load truststore from {0} : {1} ", new Object[]{trustStoreFile, e.getMessage()});
        }
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        if (newConfiguration == null) {
            return;
        }
        LOG.log(Level.FINE, "Reload truststore");
        this.currentConfiguration = newConfiguration;
        certificateAuthorities.clear();
        loadTrustStore();
    }

}
