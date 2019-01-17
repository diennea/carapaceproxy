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
package httpproxy.server.certiticates;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.security.KeyPair;
import java.security.KeyStore;
import java.util.logging.Level;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 *
 * Manager for ACME certificates and keys storage.
 * 
 * @author paolo.venturi
 */
public class DynamicCertificatesStore {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DynamicCertificatesStore.class.getName());

    // File name of the User Key Pair
    public static final String USER_KEY_FILE = "acme_client.key";
    public static final String KEY_FILE = ".key";
    public static final String KEYSTORE_FORMAT = "PKCS12";
    public static final String KEYSTORE_FILE = ".p12";
    public static final String KEYSTORE_CERT_ALIAS = "cert";
    static final char[] KEYSTORE_PW = "".toCharArray();

    // RSA key size of generated key pairs
    private static final int KEY_SIZE = 2048;

    private final File basePath;

    public DynamicCertificatesStore(File basePath) {
        this.basePath = basePath;
    }

    public KeyPair loadOrCreateUserKey() throws DynamicCertificateStoreException {
        File userKeyFile = new File(basePath, USER_KEY_FILE);
        if (userKeyFile.exists()) {
            // If there is a key file, read it          
            try (Reader r = new InputStreamReader(new FileInputStream(userKeyFile), "UTF-8")) {
                return KeyPairUtils.readKeyPair(r);
            } catch (IOException ex) {
                throw new DynamicCertificateStoreException("Unable to get user key at path: " + basePath, ex);
            }
        } else {
            // If there is none, create a new key pair and save it
            KeyPair userKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(userKeyFile), "UTF-8")) {
                KeyPairUtils.writeKeyPair(userKeyPair, w);
            } catch (IOException ex) {
                throw new DynamicCertificateStoreException("Unable to create user key at path: " + basePath, ex);
            }

            return userKeyPair;
        }
    }

    public KeyPair loadOrCreateKeyForDomain(String domain) throws DynamicCertificateStoreException {
        File domainKeyFile = new File(basePath, domain + KEY_FILE);
        if (domainKeyFile.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(domainKeyFile), "UTF-8")) {
                return KeyPairUtils.readKeyPair(r);
            } catch (IOException ex) {
                throw new DynamicCertificateStoreException("Unable to get domain keys for domain " + domain + " at path: " + basePath, ex);
            }
        } else {
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(domainKeyFile), "UTF-8")) {
                KeyPairUtils.writeKeyPair(domainKeyPair, w);
            } catch (IOException ex) {
                throw new DynamicCertificateStoreException("Unable to create domain keys for domain " + domain + " at path: " + basePath, ex);
            }

            return domainKeyPair;
        }
    }

    @SuppressFBWarnings(value="OBL_UNSATISFIED_OBLIGATION", justification="https://github.com/spotbugs/spotbugs/issues/432")
    public void saveCertificate(DynamicCertificate certificate) throws DynamicCertificateStoreException {
        String domain = certificate.getHostname();        
        File file = new File(basePath, domain + KEYSTORE_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_FORMAT);
            keyStore.load(null, null);
            keyStore.setKeyEntry(KEYSTORE_CERT_ALIAS, certificate.getKeys().getPrivate(), KEYSTORE_PW, certificate.getChain());
            keyStore.store(out, KEYSTORE_PW);
            LOG.log(Level.INFO, "Certificate saved at: " + basePath);

            certificate.setSslCertificateFile(file);
        } catch (Exception e) {
            throw new DynamicCertificateStoreException("Unable to store the certificate for domain " + domain + " at path: " + basePath, e);
        }
    }

    public static class DynamicCertificateStoreException extends Exception {
        public DynamicCertificateStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
