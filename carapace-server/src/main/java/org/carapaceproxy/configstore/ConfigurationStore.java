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
package org.carapaceproxy.configstore;

import java.security.KeyPair;
import java.util.Properties;
import java.util.function.BiConsumer;

/**
 * Stores configuration
 *
 * @author enrico.olivelli
 */
public interface ConfigurationStore extends AutoCloseable {

    String getProperty(String key, String defaultValue);

    void forEach(BiConsumer<String, String> consumer);

    void forEach(String prefix, BiConsumer<String, String> consumer);

    default Properties asProperties(String prefix) {
        Properties copy = new Properties();
        this.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                copy.put(k.substring(prefix.length() + 1), v);
            }
        });
        return copy;
    }

    @Override
    default void close() {
    }

    /**
     * Persist (if supported) the new configuration
     *
     * @param newConfigurationStore
     */
    default void commitConfiguration(ConfigurationStore newConfigurationStore) {
    }

    KeyPair loadAcmeUserKeyPair();

    boolean saveAcmeUserKey(KeyPair pair);

    KeyPair loadKeyPairForDomain(String domain);
    
    boolean saveKeyPairForDomain(KeyPair pair, String domain, boolean update);

    CertificateData loadCertificateForDomain(String domain);

    void saveCertificate(CertificateData cert);

    void reload();

    void saveAcmeChallengeToken(String id, String data);

    String loadAcmeChallengeToken(String id);

    void deleteAcmeChallengeToken(String id);

}
