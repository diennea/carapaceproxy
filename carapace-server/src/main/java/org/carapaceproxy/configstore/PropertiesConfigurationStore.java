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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;

/**
 * Reads configuration from a Java properties file
 *
 * @author enrico.olivelli
 */
public class PropertiesConfigurationStore implements ConfigurationStore {

    // volatile: replaceState swaps them, and readers are not synchronized
    private volatile Properties properties;
    private volatile ConcurrentHashMap<String, CertificateData> certificates = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, KeyPair> domainsKeyPair = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> acmeChallengeTokens = new ConcurrentHashMap<>();
    private volatile KeyPair acmeUserKey;

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
    public void removeCertificate(final String certId) {
        this.certificates.remove(certId);
        this.removePropertiesAtId("certificate", certId);
    }

    private void removePropertiesAtId(final String prefix, final String id) {
        findPropertyPrefix(prefix, id).ifPresent(indexedPrefix -> {
            final var iterator = properties.propertyNames().asIterator();
            final var spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            final var propertyNames = StreamSupport
                    .stream(spliterator, false)
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(it -> it.startsWith(indexedPrefix))
                    .collect(Collectors.toUnmodifiableSet());
            for (final var propertyName : propertyNames) {
                properties.remove(propertyName);
            }
        });
    }

    /**
     * Search for a property with the format {@code <prefix>.<some-number>.id} that matches the provided ID.
     * If found, it is returned to the caller; if not, an empty option will be the result.
     * <br>
     * Please note that if many IDs are present that would match, only <b>the first</b> will be returned,
     * as there should have been only one anyway...
     *
     * @param prefix the prefix to look for
     * @param id     the ID to look for inside the prefix
     * @return the result of the search
     */
    private Optional<String> findPropertyPrefix(final String prefix, final String id) {
        final var max = findMaxIndexForPrefix(prefix);
        for (int index = 0; index <= max; index++) {
            String indexedPrefix = prefix + "." + index + ".";
            if (id.equals(properties.getProperty(indexedPrefix + "id"))) {
                return Optional.of(indexedPrefix);
            }
        }
        return Optional.empty();
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

    @Override
    public StoreSnapshot exportState() {
        final Map<String, String> config = new HashMap<>();
        properties.forEach((k, v) -> config.put(k.toString(), v.toString()));
        final Map<String, StoreSnapshot.EncodedKeyPair> keyPairs = new HashMap<>();
        domainsKeyPair.forEach((domain, pair) -> keyPairs.put(domain, encode(pair)));
        final var acmeKey = acmeUserKey;
        final Map<String, StoreSnapshot.EncodedKeyPair> acmeAccountKeys = acmeKey == null
                ? Map.of()
                : Map.of(StoreSnapshot.LETSENCRYPT, encode(acmeKey));
        final Map<String, StoreSnapshot.CertificateRow> rows = new HashMap<>();
        try {
            for (var entry : certificates.entrySet()) {
                rows.put(entry.getKey(), ConfigurationStoreUtils.toCertificateRow(entry.getValue()));
            }
        } catch (IOException err) {
            throw new ConfigurationStoreException(err);
        }
        return new StoreSnapshot(config, keyPairs, acmeAccountKeys, rows);
    }

    @Override
    public synchronized void replaceState(StoreSnapshot snapshot, Runnable preCommitCheck) {
        // decode everything first, so that a bad value leaves the current state untouched
        final var newProperties = new Properties();
        newProperties.putAll(snapshot.config());
        final var newCertificates = new ConcurrentHashMap<String, CertificateData>();
        final var newKeyPairs = new ConcurrentHashMap<String, KeyPair>();
        final KeyPair newAcmeUserKey;
        try {
            for (var entry : snapshot.certificates().entrySet()) {
                newCertificates.put(entry.getKey(), ConfigurationStoreUtils.toCertificateData(entry.getKey(), entry.getValue()));
            }
            for (var entry : snapshot.keyPairs().entrySet()) {
                newKeyPairs.put(entry.getKey(), decode(entry.getValue()));
            }
            final var acmeKey = snapshot.acmeAccountKeys().get(StoreSnapshot.LETSENCRYPT);
            newAcmeUserKey = acmeKey == null ? null : decode(acmeKey);
        } catch (IOException | GeneralSecurityException err) {
            throw new ConfigurationStoreException(err);
        }
        preCommitCheck.run();
        properties = newProperties;
        certificates = newCertificates;
        domainsKeyPair = newKeyPairs;
        acmeUserKey = newAcmeUserKey;
    }

    /**
     * Encodes a key pair the way the database stores it.
     *
     * @param pair the key pair
     * @return the encoded key pair
     */
    private static StoreSnapshot.EncodedKeyPair encode(KeyPair pair) {
        return new StoreSnapshot.EncodedKeyPair(
                ConfigurationStoreUtils.base64EncodeKey(pair.getPrivate()),
                ConfigurationStoreUtils.base64EncodeKey(pair.getPublic())
        );
    }

    /**
     * Decodes a key pair stored by the database.
     *
     * @param pair the encoded key pair
     * @return the key pair
     * @throws GeneralSecurityException if a key cannot be decoded
     */
    private static KeyPair decode(StoreSnapshot.EncodedKeyPair pair) throws GeneralSecurityException {
        return new KeyPair(
                ConfigurationStoreUtils.base64DecodePublicKey(pair.publicKey()),
                ConfigurationStoreUtils.base64DecodePrivateKey(pair.privateKey())
        );
    }

    public void addConnectionPool(final ConnectionPoolConfiguration connectionPool) {
        saveConnectionPool(connectionPool, findMaxIndexForPrefix("connectionpool") + 1);
    }

    private void saveConnectionPool(final ConnectionPoolConfiguration connectionPool, final int index) {
        final var prefix = "connectionpool." + index + ".";
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

    public void updateConnectionPool(final ConnectionPoolConfiguration connectionPool) {
        final var max = findMaxIndexForPrefix("connectionpool");
        for (int index = 0; index <= max; index++) {
            final var prefix = "connectionpool." + index + ".";
            final var id = properties.getProperty(prefix + "id", null);
            if (connectionPool.getId().equals(id)) {
                saveConnectionPool(connectionPool, index);
                return;
            }
        }
    }

    public void deleteConnectionPool(final String id) {
        this.removePropertiesAtId("connectionpool", id);
    }
}
