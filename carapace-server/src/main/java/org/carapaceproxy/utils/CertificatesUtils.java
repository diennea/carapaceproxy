/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.utils;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Utilitis for Certificates storing as Keystores
 *
 * @author paolo
 */
public final class CertificatesUtils {

    private static final String KEYSTORE_FORMAT = "PKCS12";
    private static final String KEYSTORE_CERT_ALIAS = "cert-chain";
    public static final char[] KEYSTORE_PW = new char[0];

    private static final long DAY_TO_MILLIS = 24 * 60 * 60 * 1_000;

    /**
     * @param chain to store into a keystore
     * @param key   private key for the chain
     * @return keystore data
     * @throws GeneralSecurityException
     */
    public static byte[] createKeystore(Certificate[] chain, PrivateKey key) throws GeneralSecurityException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_FORMAT);
            keyStore.load(null, KEYSTORE_PW);
            keyStore.setKeyEntry(KEYSTORE_CERT_ALIAS, key, KEYSTORE_PW, chain);
            keyStore.store(os, KEYSTORE_PW);
            return os.toByteArray();
        } catch (IOException ex) {
            throw new GeneralSecurityException(ex);
        }
    }

    /**
     * To read a certificate chain from KeyStore data.
     *
     * @param data keystore data.
     * @return certificate chain contained into the keystore.
     * @throws GeneralSecurityException
     */
    public static Certificate[] readChainFromKeystore(byte[] data) throws GeneralSecurityException {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_FORMAT);
            keyStore.load(is, KEYSTORE_PW);
            String alias = keyStore.aliases().nextElement();
            return keyStore.getCertificateChain(alias);
        } catch (IOException ex) {
            throw new GeneralSecurityException(ex);
        }
    }

    /**
     * To read a certificate chain from a KeyStore.
     *
     * @param keystore keystore.
     * @return certificate chain contained into the keystore.
     * @throws GeneralSecurityException
     */
    public static Certificate[] readChainFromKeystore(KeyStore keystore) throws GeneralSecurityException {
        Iterator<String> iter = keystore.aliases().asIterator();
        while (iter.hasNext()) {
            Certificate[] chain = keystore.getCertificateChain(iter.next());
            if (chain != null && chain.length > 0) {
                return chain;
            }
        }

        return new Certificate[0];
    }

    /**
     * @param data keystore data.
     * @return whether a valid keystore can be retrived from data.
     */
    public static boolean validateKeystore(byte[] data) throws GeneralSecurityException {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_FORMAT);
            keyStore.load(is, KEYSTORE_PW);
            return keyStore.aliases().hasMoreElements();
        } catch (IOException ex) {
            return false;
        }
    }

    public static KeyStore loadKeyStoreFromFile(String filename, String password, File basePath)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        File sslCertFile = filename.startsWith("/") ? new File(filename) : new File(basePath, filename);
        sslCertFile = sslCertFile.getAbsoluteFile();
        KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
        try (FileInputStream in = new FileInputStream(sslCertFile)) {
            ks.load(in, password.trim().toCharArray());
        }

        return ks;
    }

    public static Map<String, X509Certificate> loadCaCerts(TrustManagerFactory trustManagerFactory) {
        final Map<String, X509Certificate> certificateAuthorities = new HashMap<>();
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                for (X509Certificate ca : ((X509TrustManager) trustManager).getAcceptedIssuers()) {
                    certificateAuthorities.put(ca.getSubjectX500Principal().getName(), ca);
                }
            }
        }
        return certificateAuthorities;
    }

    public static KeyStore loadKeyStoreData(byte[] data, String password)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
        try (ByteArrayInputStream is = new ByteArrayInputStream(data)) {
            ks.load(is, password.trim().toCharArray());
        }
        return ks;
    }


    public static boolean isCertificateExpired(Date expiringDate, int daysBeforeRenewal) throws GeneralSecurityException {
        if (expiringDate == null) {
            return false;
        }

        return System.currentTimeMillis() + (daysBeforeRenewal * DAY_TO_MILLIS) >= expiringDate.getTime();
    }

    /**
     * Extract certificate private key
     * @param data Certificate data.
     * @param password Private key password.
     * @return PrivateKey.
     * @throws Exception
     */
    public static PrivateKey loadPrivateKey(byte[] data, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
        ks.load(new ByteArrayInputStream(data), password.trim().toCharArray());
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, password.trim().toCharArray());
        return key;
    }

    /**
     * Compare two certificates chain.
     *
     * @param c1 a chain
     * @param c2 another chain
     * @return true whether the chains are the same.
     */
    public static boolean compareChains(Certificate[] c1, Certificate[] c2) {
        if (c1 == null && c2 != null || c2 == null && c1 != null) {
            return false;
        }
        if (c1.length != c2.length) {
            return false;
        }
        for (int i = 0; i < c1.length; i++) {
            try {
                if (!Arrays.equals(c1[i].getEncoded(), c2[i].getEncoded())) {
                    return false;
                }
            } catch (CertificateEncodingException ex) {
                return false;
            }
        }
        return true;
    }

}
