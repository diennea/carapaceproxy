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

import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.utils.StringUtils;
import org.shredzone.acme4j.toolbox.JSON;

public final class ConfigurationStoreUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigurationStoreUtils() {
    }

    public static String base64EncodeKey(Key key) {
        byte[] data = key.getEncoded();
        if (key instanceof PrivateKey) { // Store Private Key
            data = new PKCS8EncodedKeySpec(data).getEncoded();
        } else if (key instanceof PublicKey) { // Store Public Key
            data = new X509EncodedKeySpec(data).getEncoded();
        }
        return Base64.getEncoder().encodeToString(data);
    }

    public static PrivateKey base64DecodePrivateKey(String key) throws GeneralSecurityException {
        Base64.Decoder dec = Base64.getDecoder();
        PKCS8EncodedKeySpec keySpecPKCS8 = new PKCS8EncodedKeySpec(dec.decode(key));
        return KeyFactory.getInstance("RSA").generatePrivate(keySpecPKCS8);
    }

    public static PublicKey base64DecodePublicKey(String key) throws GeneralSecurityException {
        Base64.Decoder dec = Base64.getDecoder();
        X509EncodedKeySpec keySpecX509 = new X509EncodedKeySpec(dec.decode(key));
        return KeyFactory.getInstance("RSA").generatePublic(keySpecX509);
    }

    public static String base64EncodeCertificateChain(Certificate[] chain, PrivateKey key) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(createKeystore(chain, key));
    }

    public static Certificate[] base64DecodeCertificateChain(String chain) throws GeneralSecurityException {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        byte[] data = Base64.getDecoder().decode(chain);
        return readChainFromKeystore(data);
    }

    /**
     * Parses the pending ACME challenges as stored.
     *
     * @param challengesData the JSON object of challenge JSON texts, by domain
     * @return the challenges by domain, {@code null} if the text is blank
     * @throws JsonProcessingException if the text is not a JSON object of strings
     */
    public static Map<String, JSON> parseChallengesData(String challengesData) throws JsonProcessingException {
        if (StringUtils.isBlank(challengesData)) {
            return null;
        }
        final var map = MAPPER.readValue(challengesData, new TypeReference<Map<String, String>>() {});
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> JSON.parse(e.getValue())));
    }

    /**
     * Formats the pending ACME challenges to be stored.
     *
     * @param challengesData the challenges by domain, may be {@code null}
     * @return the JSON text, {@code null} if there is no challenge
     * @throws JsonProcessingException if the challenges cannot be written
     */
    public static String formatChallengesData(Map<String, JSON> challengesData) throws JsonProcessingException {
        if (challengesData == null || challengesData.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(challengesData.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()))
        );
    }

    /**
     * Builds a certificate from its stored row, the way the stores read it.
     *
     * @param domain the domain of the certificate
     * @param row    the stored row
     * @return the certificate
     * @throws IOException              if the pending order or the pending challenges cannot be parsed
     * @throws IllegalArgumentException if the state is unknown
     */
    public static CertificateData toCertificateData(String domain, StoreSnapshot.CertificateRow row) throws IOException {
        return new CertificateData(
                domain,
                Set.copyOf(row.subjectAltNames()),
                row.chain(),
                DynamicCertificateState.fromStorableFormat(row.state()),
                row.pendingOrder() != null ? URI.create(row.pendingOrder()).toURL() : null,
                parseChallengesData(row.pendingChallenges()),
                row.attemptCount(),
                row.message()
        );
    }

    /**
     * Turns a certificate into its stored row.
     *
     * @param cert the certificate
     * @return the row, with the subject alternative names normalized, see {@link #normalizeSubjectAltNames(Stream)}
     * @throws JsonProcessingException if the pending challenges cannot be written
     */
    public static StoreSnapshot.CertificateRow toCertificateRow(CertificateData cert) throws JsonProcessingException {
        return new StoreSnapshot.CertificateRow(
                // one name may hold several comma-separated ones: the database column would merge them anyway
                cert.getSubjectAltNames() == null ? null : normalizeSubjectAltNames(cert.getSubjectAltNames().stream()),
                cert.getChain(),
                cert.getState().toStorableFormat(),
                cert.getPendingOrderLocation() != null ? cert.getPendingOrderLocation().toString() : null,
                formatChallengesData(cert.getPendingChallengesData()),
                cert.getAttemptsCount(),
                cert.getMessage()
        );
    }

    /**
     * Normalizes subject alternative names the way the store reads them back.
     *
     * @param names the names, each may hold several comma-separated ones
     * @return the names split, trimmed, without blanks and duplicates, sorted
     */
    static List<String> normalizeSubjectAltNames(Stream<String> names) {
        return names
                .flatMap(name -> Arrays.stream(name.split(ConfigurationStore.PROPERTY_VALUES_SEPARATOR)))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

}
