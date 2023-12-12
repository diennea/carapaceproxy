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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Configuration storage implementation tha reads the configuration from a Java {@link Properties} file.
 * It resides in memory,
 * and it does <b>not</b> support {@link #commitConfiguration(ConfigurationStore) commiting} changes.
 *
 * @author enrico.olivelli
 */
public class PropertiesConfigurationStore implements ConfigurationStore {

    private final Properties properties;
    private final ConcurrentHashMap<String, CertificateData> certificates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KeyPair> domainsKeyPair = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> acmeChallengeTokens = new ConcurrentHashMap<>();
    private KeyPair acmeUserKey;

    public PropertiesConfigurationStore(Properties properties) {
        this.properties = properties;
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<String, String> consumer) {
        properties.forEach((k, v) -> {
            consumer.accept(k.toString(), v.toString());
        });
    }

    @Override
    public void forEach(String prefix, BiConsumer<String, String> consumer) {
        properties.forEach((k, v) -> {
            if (k.toString().startsWith(prefix)) {
                consumer.accept(k.toString().substring(prefix.length()), v.toString());
            }
        });
    }

    @Override
    public KeyPair loadAcmeUserKeyPair() {
        return acmeUserKey;
    }

    @Override
    public boolean saveAcmeUserKey(KeyPair pair) {
        if (acmeUserKey == null) {
            acmeUserKey = pair;
            return true;
        }
        return false;
    }

    @Override
    public KeyPair loadKeyPairForDomain(String domain) {
        return domainsKeyPair.get(domain);
    }

    @Override
    public boolean saveKeyPairForDomain(KeyPair pair, String domain, boolean update) {
        if (update || !domainsKeyPair.containsKey(domain)) {
            domainsKeyPair.put(domain, pair);
            return true;
        }
        return false;
    }

    @Override
    public CertificateData loadCertificateForDomain(String domain) {
        return certificates.get(domain);
    }

    @Override
    public void saveCertificate(CertificateData cert) {
        certificates.put(cert.getDomain(), cert);
    }

    @Override
    public void reload() {
    }

    @Override
    public void saveAcmeChallengeToken(String id, String data) {
        acmeChallengeTokens.put(id, data);
    }

    @Override
    public String loadAcmeChallengeToken(String id) {
        return acmeChallengeTokens.get(id);
    }

    @Override
    public void deleteAcmeChallengeToken(String id) {
        acmeChallengeTokens.remove(id);
    }

}
