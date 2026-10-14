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
package org.carapaceproxy.configstore;

import herddb.utils.BooleanHolder;
import java.security.KeyPair;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.carapaceproxy.server.config.ConfigurationNotValidException;

/**
 * Stores configuration
 *
 * @author enrico.olivelli
 */
public interface ConfigurationStore extends AutoCloseable {

    String PROPERTY_VALUES_SEPARATOR = ",";

    default String toStringConfiguration() {
        Set<String> props = new TreeSet<>();
        forEach((k, v) -> props.add(k + "=" + v));
        StringBuilder builder = new StringBuilder();
        props.forEach(p -> builder.append(p).append("\n"));
        return builder.toString();
    }

    String getProperty(String key, String defaultValue);

    default int getInt(String key, int defaultValue) throws ConfigurationNotValidException {
        String property = getProperty(key, defaultValue + "").trim();
        if (property.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(property);
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }

    default long getLong(String key, long defaultValue) throws ConfigurationNotValidException {
        String property = getProperty(key, defaultValue + "").trim();
        if (property.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(property);
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }

    default String getString(String key, String defaultValue) throws ConfigurationNotValidException {
        String property = getProperty(key, defaultValue);
        if (property == null || property.isBlank()) {
            return defaultValue;
        }
        return property.trim();
    }

    default boolean getBoolean(String key, boolean defaultValue) throws ConfigurationNotValidException {
        String property = getProperty(key, defaultValue + "").trim();
        if (property.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(property);
    }

    default Set<String> getValues(String key) throws ConfigurationNotValidException {
        return getValues(key, Set.of());
    }

    default Set<String> getValues(String key, Set<String> defaultValue) throws ConfigurationNotValidException {
        final var values = getString(key, "");
        if (values.isBlank()) {
            return defaultValue;
        }
        return Set.of(values.replaceAll(" ", "").split(PROPERTY_VALUES_SEPARATOR));
    }

    default String getClassname(String key, String defaultValue) throws ConfigurationNotValidException {
        String classname = getString(key, defaultValue);
        try {
            if (classname != null) {
                Class.forName(classname, true, Thread.currentThread().getContextClassLoader());
            }
            return classname;
        } catch (ClassNotFoundException err) {
            throw new ConfigurationNotValidException("Invalid class value '" + classname + "' for parameter '" + key + "' : " + err);
        }
    }

    void forEach(BiConsumer<String, String> consumer);

    void forEach(String prefix, BiConsumer<String, String> consumer);

    /**
     *
     * @param prefix prefix for properties to fetch. Whether null, all properties will be fetched.
     * @return properties with key starting with prefix.
     */
    default Properties asProperties(String prefix) {
        Properties copy = new Properties();
        this.forEach((k, v) -> {
            if (prefix == null) {
                copy.put(k, v);
            } else if (k.startsWith(prefix)) {
                copy.put(k.substring(prefix.length() + 1), v);
            }
        });
        return copy;
    }

    /**
     *
     * @param prefix before the index of the property.
     * @return max index for the property or -1 if no property matches the prefix.
     */
    default int findMaxIndexForPrefix(String prefix) {
        Set<Integer> usedIndexes = new HashSet<>();
        this.forEach(prefix + ".", (k, v) -> {
            String[] split = k.split("\\.");
            try {
                if (split.length > 0) {
                    usedIndexes.add(Integer.parseInt(split[0]));
                }
            } catch (NumberFormatException ignored) {

            }
        });

        return usedIndexes.stream().max(Comparator.naturalOrder()).orElse(-1);
    }

    /**
     * Check if any of the properties match a predicate.
     *
     * @param predicate the predicate to execute against all the properties
     * @return whether exist at least one property matching to passed predicate.
     */
    default boolean anyPropertyMatches(BiPredicate<String, String> predicate) {
        BooleanHolder any = new BooleanHolder(false);
        this.forEach((k, v) -> {
            if (predicate.test(k, v)) {
                any.value = true;
            }
        });
        return any.value;
    }

    @Override
    default void close() {
    }

    /**
     * Persist (if supported) the new configuration
     *
     * @param newConfigurationStore the new configuration store to persist
     */
    default void commitConfiguration(ConfigurationStore newConfigurationStore) {
    }

    KeyPair loadAcmeUserKeyPair();

    boolean saveAcmeUserKey(KeyPair pair);

    KeyPair loadKeyPairForDomain(String domain);

    boolean saveKeyPairForDomain(KeyPair pair, String domain, boolean update);

    CertificateData loadCertificateForDomain(String domain);

    void saveCertificate(CertificateData cert);

    void removeCertificate(String certId);

    void reload();

    void saveAcmeChallengeToken(String id, String data);

    String loadAcmeChallengeToken(String id);

    void deleteAcmeChallengeToken(String id);

    /**
     * Reads the whole state of the store: configuration, key pairs, ACME account key and certificates.
     * <br>
     * ACME challenge tokens are not part of the state.
     *
     * @return a copy of the stored state, values in the encodings the database uses
     * @throws ConfigurationStoreException if the store cannot be read
     */
    StoreSnapshot exportState();

    /**
     * Replaces the whole state of the store with the given snapshot.
     * <br>
     * All or nothing: after a normal return the store holds exactly the snapshot; if it throws, the store holds either the
     * previous state or exactly the snapshot (when the outcome of the commit is unknown), never a mix of the two.
     * Repeating the call is the way out of the unknown outcome.
     * <br>
     * ACME challenge tokens are not part of the state and are left untouched.
     * The guards against overwriting, like the one on the ACME account key, do not apply: everything is replaced.
     * <br>
     * Concurrent readers are not isolated from the replacement: callers must hold the write locks.
     *
     * @param snapshot       the state to store, already {@link StoreSnapshot#validate() validated}
     * @param preCommitCheck run right before the commit; it aborts the replacement by throwing
     * @throws ConfigurationStoreException if the store cannot be read or written
     * @throws RuntimeException            whatever {@code preCommitCheck} throws, after rolling back
     */
    void replaceState(StoreSnapshot snapshot, Runnable preCommitCheck);

    /**
     * Preflight hook: checks that the store can take a write right now, before anything is written.
     *
     * @throws ConfigurationNotValidException if the store would refuse or lose the write
     */
    default void checkWritable() throws ConfigurationNotValidException {
    }
}
