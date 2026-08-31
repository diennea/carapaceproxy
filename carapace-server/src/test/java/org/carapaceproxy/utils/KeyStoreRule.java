package org.carapaceproxy.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that generates a self-signed keystore and matching truststore once before all tests.
 * Register it with {@code @RegisterExtension static KeyStoreRule ... = new KeyStoreRule();}.
 */
public class KeyStoreRule implements BeforeAllCallback, AfterAllCallback {

    private static final String KEYSTORE_ALIAS = "server";
    private static final int KEY_SIZE = 2048;
    private static final long VALIDITY_DAYS = 365;
    private static final char[] PASSWORD = "password".toCharArray();
    private final char[] keystorePassword;
    private final char[] keyPassword;

    private File keyStoreFile;
    private KeyStore keyStore;
    private File trustStoreFile;
    private KeyStore trustStore;
    private KeyPair keyPair;
    private X509Certificate certificate;
    private Provider securityProvider;
    private boolean removeProvider;

    public KeyStoreRule() {
        this.keystorePassword = PASSWORD;
        this.keyPassword = PASSWORD;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        initSecurityProvider();
        initKeyPair();
        initCertificate();
        initKeyStore();
        initTrustStore();
    }

    private void initTrustStore() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry(KEYSTORE_ALIAS, certificate);
        trustStoreFile = File.createTempFile("test-truststore-", ".p12");
        trustStoreFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(trustStoreFile)) {
            trustStore.store(fos, keystorePassword);
        }
    }

    private void initKeyStore() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                KEYSTORE_ALIAS,
                keyPair.getPrivate(),
                keyPassword,
                new X509Certificate[]{certificate}
        );
        keyStoreFile = File.createTempFile("test-keystore-", ".p12");
        keyStoreFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(keyStoreFile)) {
            keyStore.store(fos, keystorePassword);
        }
    }

    private void initCertificate() throws OperatorCreationException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException {
        final X500Name distinguishedName = new X500Name("CN=localhost");
        final long now = System.currentTimeMillis();
        final BigInteger serial = BigInteger.valueOf(now);
        final Date notBefore = new Date(now - 60_000L); // 1 minute ago to handle clock skew
        final Date notAfter = new Date(now + VALIDITY_DAYS * 24 * 60 * 60 * 1000);
        final JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                distinguishedName, serial, notBefore, notAfter, distinguishedName, keyPair.getPublic()
        );
        final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        final X509CertificateHolder certificateHolder = certificateBuilder.build(signer);
        certificate = new JcaX509CertificateConverter().getCertificate(certificateHolder);
        certificate.verify(keyPair.getPublic());
    }

    private void initKeyPair() throws NoSuchAlgorithmException {
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", securityProvider);
        keyPairGenerator.initialize(KEY_SIZE);
        keyPair = keyPairGenerator.generateKeyPair();
    }

    private void initSecurityProvider() {
        final Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider != null) {
            this.securityProvider = provider;
            this.removeProvider = false;
        } else {
            this.securityProvider = new BouncyCastleProvider();
            Security.addProvider(securityProvider);
            this.removeProvider = true;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (keyStoreFile != null && keyStoreFile.exists()) {
            if (!keyStoreFile.delete()) {
                System.err.println("Unable to delete keystore file: " + keyStoreFile.getAbsolutePath());
            }
        }
        if (trustStoreFile != null && trustStoreFile.exists()) {
            if (!trustStoreFile.delete()) {
                System.err.println("Unable to delete truststore file: " + trustStoreFile.getAbsolutePath());
            }
        }
        if (this.removeProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        }
    }

    /**
     * @return The file containing the PKCS12 keystore with the server's private key and certificate
     */
    public File getKeyStoreFile() {
        return keyStoreFile;
    }

    /**
     * @return The keystore loaded in memory (PKCS12 format)
     */
    public KeyStore getKeyStore() {
        return keyStore;
    }

    /**
     * @return The file containing the PKCS12 truststore with the CA certificate
     */
    public File getTrustStoreFile() {
        return trustStoreFile;
    }

    /**
     * @return The truststore loaded in memory that trusts the generated certificate
     */
    public KeyStore getTrustStore() {
        return trustStore;
    }

    /**
     * @return The password used to access the keystore file
     */
    public char[] getKeyStorePassword() {
        return keystorePassword;
    }

    /**
     * @return The password used to access individual private keys in the keystore
     */
    public char[] getKeyPassword() {
        return keyPassword;
    }

    /**
     * @return The alias under which the key and certificate are stored
     */
    public String getKeyAlias() {
        return KEYSTORE_ALIAS;
    }

    /**
     * @return The generated X.509 certificate
     */
    public X509Certificate getCertificate() {
        return certificate;
    }

    /**
     * @return The generated key pair
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Creates a TrustManagerFactory initialized with the truststore.
     * Useful for configuring SSL contexts.
     *
     * @return A TrustManagerFactory that trusts the generated certificate
     */
    public TrustManagerFactory getTrustManagerFactory() throws NoSuchAlgorithmException, KeyStoreException {
        final String algorithm = TrustManagerFactory.getDefaultAlgorithm();
        final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
        trustManagerFactory.init(trustStore);
        return trustManagerFactory;
    }
}
