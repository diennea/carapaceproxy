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

import static org.carapaceproxy.configstore.HerdDBConfigurationStore.ACME_USER_KEY;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.StringUtils;

/**
 * The whole content of a dynamic {@link ConfigurationStore}, ACME challenge tokens excluded, values in the database
 * encoding. Maps are sorted and unmodifiable; {@code null} maps become empty ones.
 *
 * @param config          the dynamic configuration properties
 * @param keyPairs        the key pair of every domain, by domain; never holds the ACME account key
 * @param acmeAccountKeys the ACME account key pairs, by provider
 * @param certificates    the stored dynamic certificates, by domain
 */
public record StoreSnapshot(
        Map<String, String> config,
        Map<String, EncodedKeyPair> keyPairs,
        Map<String, EncodedKeyPair> acmeAccountKeys,
        Map<String, CertificateRow> certificates) {

    /**
     * Copies every map, so that the snapshot cannot change behind the caller.
     *
     * @param config          the dynamic configuration properties
     * @param keyPairs        the key pair of every domain, by domain
     * @param acmeAccountKeys the ACME account key pairs, by provider
     * @param certificates    the stored dynamic certificates, by domain
     */
    public StoreSnapshot {
        config = copy(config);
        keyPairs = copy(keyPairs);
        acmeAccountKeys = copy(acmeAccountKeys);
        certificates = copy(certificates);
    }

    /**
     * Copies a map sorted by key; a TreeMap rather than Map.copyOf, so that null values reach validate() and fail there with a readable message.
     *
     * @param map the map, may be {@code null}
     * @param <V> the type of the values
     * @return an unmodifiable sorted copy, empty for {@code null}
     */
    private static <V> Map<String, V> copy(final Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(map));
    }

    /**
     * Checks that the store could read back every value of this snapshot.
     *
     * @throws ConfigurationNotValidException naming the domain, provider or property and the field that cannot be read
     */
    public void validate() throws ConfigurationNotValidException {
        if (config.isEmpty()) {
            throw new ConfigurationNotValidException("The configuration is empty");
        }
        for (final var entry : config.entrySet()) {
            if (entry.getValue() == null) {
                throw new ConfigurationNotValidException("Property " + entry.getKey() + " has no value");
            }
        }
        for (final var entry : keyPairs.entrySet()) {
            validateDomain("keyPairs", entry.getKey());
            validateKeyPair("keyPairs of domain " + entry.getKey(), entry.getValue());
        }
        for (final var entry : acmeAccountKeys.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                throw new ConfigurationNotValidException("Blank provider in acmeAccountKeys");
            }
            validateKeyPair("acmeAccountKeys of provider " + entry.getKey(), entry.getValue());
        }
        for (final var entry : certificates.entrySet()) {
            validateDomain("certificates", entry.getKey());
            validateCertificate(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Checks a domain used as a key of the snapshot.
     *
     * @param section the section of the snapshot, for the message
     * @param domain  the domain
     * @throws ConfigurationNotValidException if the domain is blank, not trimmed or the reserved name of an ACME account key
     */
    private static void validateDomain(final String section, final String domain) throws ConfigurationNotValidException {
        if (StringUtils.isBlank(domain) || !domain.equals(domain.trim()) || domain.startsWith(ACME_USER_KEY)) {
            throw new ConfigurationNotValidException("Invalid domain \"" + domain + "\" in " + section);
        }
    }

    /**
     * Checks that both keys of a pair can be decoded.
     *
     * @param what what the pair belongs to, for the message
     * @param pair the key pair
     * @throws ConfigurationNotValidException if the pair is missing or a key cannot be decoded
     */
    private static void validateKeyPair(final String what, final EncodedKeyPair pair) throws ConfigurationNotValidException {
        if (pair == null) {
            throw new ConfigurationNotValidException("Missing key pair in " + what);
        }
        try {
            ConfigurationStoreUtils.base64DecodePrivateKey(pair.privateKey());
        } catch (GeneralSecurityException | RuntimeException err) {
            throw new ConfigurationNotValidException("Invalid privateKey in " + what + ": " + err);
        }
        try {
            ConfigurationStoreUtils.base64DecodePublicKey(pair.publicKey());
        } catch (GeneralSecurityException | RuntimeException err) {
            throw new ConfigurationNotValidException("Invalid publicKey in " + what + ": " + err);
        }
    }

    /**
     * Checks that the store could read back a certificate row.
     *
     * @param domain the domain of the certificate
     * @param row    the certificate row
     * @throws ConfigurationNotValidException naming the field that cannot be read
     */
    private static void validateCertificate(final String domain, final CertificateRow row) throws ConfigurationNotValidException {
        final var what = "certificate of domain " + domain;
        if (row == null) {
            throw new ConfigurationNotValidException("Missing " + what);
        }
        final Set<String> names = new HashSet<>();
        for (final var san : row.subjectAltNames()) {
            // the SANs are stored as one comma-separated column, read back trimmed and without empty names
            if (StringUtils.isBlank(san) || !san.equals(san.trim()) || san.contains(",")) {
                throw new ConfigurationNotValidException("Invalid subjectAltNames \"" + san + "\" in " + what);
            }
            // and loaded into a Set.of, which refuses duplicates
            if (!names.add(san)) {
                throw new ConfigurationNotValidException("Duplicate subjectAltNames \"" + san + "\" in " + what);
            }
        }
        // the loader decodes any non-empty chain, whitespace included
        if (row.chain() != null && !row.chain().isEmpty()) {
            final Certificate[] chain;
            try {
                chain = ConfigurationStoreUtils.base64DecodeCertificateChain(row.chain());
            } catch (GeneralSecurityException | RuntimeException err) {
                throw new ConfigurationNotValidException("Invalid chain in " + what + ": " + err);
            }
            if (chain == null || chain.length == 0) {
                throw new ConfigurationNotValidException("Invalid chain in " + what + ": no certificate");
            }
        }
        try {
            DynamicCertificateState.fromStorableFormat(row.state());
        } catch (RuntimeException err) {
            throw new ConfigurationNotValidException("Unknown state \"" + row.state() + "\" in " + what);
        }
        if (row.pendingOrder() != null) {
            try {
                // as the store reads the order back: a value it refuses would stop every reload of the restored state
                URI.create(row.pendingOrder()).toURL();
            } catch (MalformedURLException | IllegalArgumentException err) {
                throw new ConfigurationNotValidException("Invalid pendingOrder in " + what + ": " + err.getMessage());
            }
        }
        try {
            ConfigurationStoreUtils.parseChallengesData(row.pendingChallenges());
        } catch (IOException | RuntimeException err) {
            // the parser quotes the offending text, which is no business of a log
            throw new ConfigurationNotValidException("Invalid pendingChallenges in " + what);
        }
        if (row.attemptCount() < 0) {
            throw new ConfigurationNotValidException("Negative attemptCount in " + what);
        }
    }

    /**
     * A key pair as the store keeps it.
     *
     * @param privateKey the private key, base64 of its PKCS#8 encoding
     * @param publicKey  the public key, base64 of its X.509 encoding
     */
    public record EncodedKeyPair(String privateKey, String publicKey) {

        /**
         * Keeps the private key out of logs and error messages.
         *
         * @return the public key only
         */
        @Override
        public String toString() {
            return "EncodedKeyPair[privateKey=***, publicKey=" + publicKey + "]";
        }
    }

    /**
     * A dynamic certificate as the store keeps it.
     *
     * @param subjectAltNames   the subject alternative names, never {@code null}
     * @param chain             the base64 of the PKCS#12 keystore holding the chain, may be {@code null}
     * @param state             the name of the {@link DynamicCertificateState}
     * @param pendingOrder      the URL of the pending ACME order, may be {@code null}
     * @param pendingChallenges the JSON text of the pending ACME challenges, may be {@code null}
     * @param attemptCount      the number of failed attempts
     * @param message           the last error message, may be {@code null}
     */
    public record CertificateRow(
            List<String> subjectAltNames,
            String chain,
            String state,
            String pendingOrder,
            String pendingChallenges,
            int attemptCount,
            String message) {

        /**
         * Copies the subject alternative names, so that the row cannot change behind the caller.
         *
         * @param subjectAltNames   the subject alternative names, {@code null} means none
         * @param chain             the base64 of the PKCS#12 keystore holding the chain
         * @param state             the name of the {@link DynamicCertificateState}
         * @param pendingOrder      the URL of the pending ACME order
         * @param pendingChallenges the JSON text of the pending ACME challenges
         * @param attemptCount      the number of failed attempts
         * @param message           the last error message
         */
        public CertificateRow {
            // not List.copyOf: a null name must reach validate()
            subjectAltNames = subjectAltNames == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(subjectAltNames));
        }

        /**
         * Keeps the chain out of logs: the keystore holds the private key too, under a well-known password.
         *
         * @return every field but the chain
         */
        @Override
        public String toString() {
            return "CertificateRow[subjectAltNames=" + subjectAltNames + ", chain=" + (chain == null ? "null" : "***")
                    + ", state=" + state + ", pendingOrder=" + pendingOrder + ", pendingChallenges=" + pendingChallenges
                    + ", attemptCount=" + attemptCount + ", message=" + message + "]";
        }
    }
}
