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
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;

/**
 * Reads configuration from a Java properties file
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
        properties.forEach((k, v) -> consumer.accept(k.toString(), v.toString()));
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

    public void saveConnectionPool(final ConnectionPoolConfiguration connectionPool) {
        final var max = findMaxIndexForPrefix("connectionpool");
        String prefix = "connectionpool.0.";
        for (int index = 0; index <= max; prefix = "connectionpool." + ++index + '.') {
            if (getProperty(prefix, null) != null) {
                // found
                break;
            }
        }
        properties.setProperty(prefix + "id", connectionPool.getId());
        properties.setProperty(prefix + "domain", connectionPool.getDomain());
        properties.setProperty(prefix + "maxconnectionsperendpoint", String.valueOf(connectionPool.getMaxConnectionsPerEndpoint()));
        properties.setProperty(prefix + "borrowtimeout", String.valueOf(connectionPool.getBorrowTimeout()));
        properties.setProperty(prefix + "connecttimeout", String.valueOf(connectionPool.getConnectTimeout()));
        properties.setProperty(prefix + "stuckrequesttimeout", String.valueOf(connectionPool.getStuckRequestTimeout()));
        properties.setProperty(prefix + "idletimeout", String.valueOf(connectionPool.getIdleTimeout()));
        properties.setProperty(prefix + "maxlifetime", String.valueOf(connectionPool.getMaxLifeTime()));
        properties.setProperty(prefix + "disposetimeout", String.valueOf(connectionPool.getDisposeTimeout()));
        properties.setProperty(prefix + "keepaliveidle", String.valueOf(connectionPool.getKeepaliveIdle()));
        properties.setProperty(prefix + "keepaliveinterval", String.valueOf(connectionPool.getKeepaliveInterval()));
        properties.setProperty(prefix + "keepalivecount", String.valueOf(connectionPool.getKeepaliveCount()));
        properties.setProperty(prefix + "enabled", String.valueOf(connectionPool.isEnabled()));
        properties.setProperty(prefix + "keepalive", String.valueOf(connectionPool.isKeepAlive()));
    }

}
