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

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.configstore.StoreSnapshot.CertificateRow;
import org.carapaceproxy.configstore.StoreSnapshot.EncodedKeyPair;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.CertificatesTestUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Test for {@link StoreSnapshot#validate()}.
 */
public class StoreSnapshotTest {

    private static final String DOMAIN = "app.example.test";
    private static final Map<String, String> CONFIG = Map.of("certificate.0.hostname", DOMAIN);

    private static EncodedKeyPair keyPair;
    private static String chain;

    @BeforeClass
    public static void setUp() throws Exception {
        final var pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        keyPair = new EncodedKeyPair(
                ConfigurationStoreUtils.base64EncodeKey(pair.getPrivate()),
                ConfigurationStoreUtils.base64EncodeKey(pair.getPublic())
        );
        chain = Base64.getEncoder().encodeToString(CertificatesTestUtils.generateSampleChainData());
    }

    private static CertificateRow row(List<String> sans, String chain, String state, String order, String challenges, int attempts) {
        return new CertificateRow(sans, chain, state, order, challenges, attempts, "");
    }

    private static CertificateRow validRow() {
        return row(List.of("www." + DOMAIN), chain, "AVAILABLE", "http://localhost/order", "{\"" + DOMAIN + "\":\"{}\"}", 0);
    }

    private static StoreSnapshot snapshot(CertificateRow row) {
        return new StoreSnapshot(CONFIG, Map.of(DOMAIN, keyPair), Map.of(StoreSnapshot.LETSENCRYPT, keyPair), Map.of(DOMAIN, row));
    }

    private static void assertInvalid(StoreSnapshot snapshot, String... messageParts) {
        final var err = assertThrows(ConfigurationNotValidException.class, snapshot::validate);
        for (final var part : messageParts) {
            assertThat(err.getMessage(), containsString(part));
        }
    }

    @Test
    public void testValidSnapshot() throws Exception {
        snapshot(validRow()).validate();
        // optional fields absent and no chain yet, as for a certificate still waiting
        snapshot(row(null, null, "WAITING", null, null, 0)).validate();
        snapshot(row(null, "", "WAITING", null, null, 0)).validate();
    }

    @Test
    public void testEmptyConfig() {
        assertInvalid(new StoreSnapshot(null, null, null, null), "configuration is empty");
    }

    @Test
    public void testBadPrivateKey() {
        final var bad = new EncodedKeyPair("not a key", keyPair.publicKey());
        assertInvalid(new StoreSnapshot(CONFIG, Map.of(DOMAIN, bad), null, null), "privateKey", DOMAIN);
    }

    @Test
    public void testBadPublicKey() {
        final var bad = new EncodedKeyPair(keyPair.privateKey(), Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertInvalid(new StoreSnapshot(CONFIG, null, Map.of(StoreSnapshot.LETSENCRYPT, bad), null), "publicKey", StoreSnapshot.LETSENCRYPT);
    }

    @Test
    public void testBadChain() {
        assertInvalid(snapshot(row(List.of(), Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), "AVAILABLE", null, null, 0)),
                "chain", DOMAIN);
        assertInvalid(snapshot(row(List.of(), "not base64!", "AVAILABLE", null, null, 0)), "chain", DOMAIN);
        assertInvalid(snapshot(row(List.of(), " ", "AVAILABLE", null, null, 0)), "chain", DOMAIN);
    }

    @Test
    public void testUnknownState() {
        assertInvalid(snapshot(row(List.of(), chain, "SOMETHING_NEW", null, null, 0)), "SOMETHING_NEW", DOMAIN);
        assertInvalid(snapshot(row(List.of(), chain, null, null, null, 0)), "state", DOMAIN);
    }

    @Test
    public void testBadPendingOrder() {
        assertInvalid(snapshot(row(List.of(), chain, "ORDERING", "not a url", null, 0)), "pendingOrder", DOMAIN);
    }

    @Test
    public void testBadPendingChallenges() {
        assertInvalid(snapshot(row(List.of(), chain, "VERIFYING", null, "{not json", 0)), "pendingChallenges", DOMAIN);
    }

    @Test
    public void testNegativeAttemptCount() {
        assertInvalid(snapshot(row(List.of(), chain, "REQUEST_FAILED", null, null, -1)), "attemptCount", DOMAIN);
    }

    @Test
    public void testSubjectAltNameWithComma() {
        assertInvalid(snapshot(row(List.of("a.example.test,b.example.test"), chain, "AVAILABLE", null, null, 0)),
                "subjectAltNames", DOMAIN);
        // the store would read them back changed: trimmed, or dropped when empty
        assertInvalid(snapshot(row(List.of("a.example.test", ""), chain, "AVAILABLE", null, null, 0)), "subjectAltNames", DOMAIN);
        assertInvalid(snapshot(row(List.of(" a.example.test"), chain, "AVAILABLE", null, null, 0)), "subjectAltNames", DOMAIN);
    }

    @Test
    public void testToStringHidesKeyMaterial() {
        assertThat(new EncodedKeyPair("PRIV", "PUB").toString(), not(containsString("PRIV")));
        assertThat(validRow().toString(), not(containsString(validRow().chain())));
    }

    @Test
    public void testInvalidDomains() {
        assertInvalid(new StoreSnapshot(CONFIG, Map.of(" ", keyPair), null, null), "domain", "keyPairs");
        assertInvalid(new StoreSnapshot(CONFIG, Map.of(" " + DOMAIN, keyPair), null, null), "domain", "keyPairs");
        assertInvalid(new StoreSnapshot(CONFIG, Map.of(HerdDBConfigurationStore.ACME_USER_KEY, keyPair), null, null),
                HerdDBConfigurationStore.ACME_USER_KEY);
        assertInvalid(new StoreSnapshot(CONFIG, null, null, Map.of("", validRow())), "domain", "certificates");
    }
}
