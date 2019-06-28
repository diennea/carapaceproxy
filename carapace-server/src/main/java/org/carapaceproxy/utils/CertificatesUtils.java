/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.carapaceproxy.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;

/**
 * Utilitis for Certificates storing as Keystores
 *
 * @author paolo
 */
public final class CertificatesUtils {

    private static final String KEYSTORE_FORMAT = "PKCS12";
    private static final String KEYSTORE_CERT_ALIAS = "cert-chain";
    private static final char[] KEYSTORE_PW = new char[0];

    /**
     *
     * @param chain to store into a keystore
     * @param key private key for the chain
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
     *
     * @param data keystore data.
     * @return certificate chain contained into the keystore.
     * @throws GeneralSecurityException
     */
    public static Certificate[] readFromKeystore(byte[] data) throws GeneralSecurityException {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_FORMAT);
            keyStore.load(is, KEYSTORE_PW);
            return keyStore.getCertificateChain(KEYSTORE_CERT_ALIAS);
        } catch (IOException ex) {
            throw new GeneralSecurityException(ex);
        }
    }

    /**
     *
     * @param data keystore data.
     * @return whether a valid keystore can be retrived from data.
     */
    public static boolean validateKeystore(byte[] data) {
        try {
            Certificate[] chain = readFromKeystore(data);
            return  chain != null && chain.length > 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
