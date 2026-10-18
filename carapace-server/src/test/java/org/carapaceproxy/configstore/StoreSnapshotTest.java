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
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import java.util.Base64;
import java.util.Collections;
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
        return new StoreSnapshot(CONFIG, Map.of(DOMAIN, keyPair), Map.of(DEFAULT_PROVIDER_NAME, keyPair), Map.of(DOMAIN, row));
    }

    @Test
    public void testValidSnapshot() throws Exception {
        snapshot(validRow()).validate();
        // optional fields absent and no chain yet, as for a certificate still waiting
        snapshot(row(null, null, "WAITING", null, null, 0)).validate();
        // an uploaded certificate has a chain and no key pair row
        new StoreSnapshot(CONFIG, null, null, Map.of(DOMAIN, validRow())).validate();
        new StoreSnapshot(CONFIG, null, null, Map.of(DOMAIN, row(null, "", "WAITING", null, null, 0))).validate();
        // one account key for every provider
        new StoreSnapshot(CONFIG, null, Map.of(DEFAULT_PROVIDER_NAME, keyPair, "otherprovider", keyPair), null).validate();
    }

    @Test
    public void testInvalidSnapshots() {
        final var badPrivate = new EncodedKeyPair("not a key", keyPair.publicKey());
        final var badPublic = new EncodedKeyPair(keyPair.privateKey(), Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        final var notAChain = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        // each snapshot, then the parts its message must name
        final List<Map.Entry<StoreSnapshot, List<String>>> cases = List.of(
                Map.entry(new StoreSnapshot(null, null, null, null), List.of("configuration is empty")),
                Map.entry(new StoreSnapshot(Collections.singletonMap("null.property", null), null, null, null), List.of("null.property", "no value")),
                Map.entry(new StoreSnapshot(CONFIG, Map.of(DOMAIN, badPrivate), null, null), List.of("privateKey", DOMAIN)),
                Map.entry(new StoreSnapshot(CONFIG, null, Map.of(DEFAULT_PROVIDER_NAME, badPublic), null),
                        List.of("publicKey", DEFAULT_PROVIDER_NAME)),
                Map.entry(new StoreSnapshot(CONFIG, null, Map.of(" ", keyPair), null), List.of("provider", "acmeAccountKeys")),
                Map.entry(snapshot(row(List.of(), notAChain, "AVAILABLE", null, null, 0)), List.of("chain", DOMAIN)),
                Map.entry(snapshot(row(List.of(), "not base64!", "AVAILABLE", null, null, 0)), List.of("chain", DOMAIN)),
                Map.entry(snapshot(row(List.of(), " ", "AVAILABLE", null, null, 0)), List.of("chain", DOMAIN)),
                Map.entry(snapshot(row(List.of(), chain, "SOMETHING_NEW", null, null, 0)), List.of("SOMETHING_NEW", DOMAIN)),
                Map.entry(snapshot(row(List.of(), chain, null, null, null, 0)), List.of("state", DOMAIN)),
                Map.entry(snapshot(row(List.of(), chain, "ORDERING", "not a url", null, 0)), List.of("pendingOrder", DOMAIN)),
                // accepted by new URL(...), refused by the parser the store reads it back with
                Map.entry(snapshot(row(List.of(), chain, "ORDERING", "https://acme.example/order 1", null, 0)), List.of("pendingOrder", DOMAIN)),
                Map.entry(snapshot(row(List.of(), chain, "VERIFYING", null, "{not json", 0)), List.of("pendingChallenges", DOMAIN)),
                Map.entry(snapshot(row(List.of(), chain, "REQUEST_FAILED", null, null, -1)), List.of("attemptCount", DOMAIN)),
                // the store would read them back changed: split, trimmed, or dropped when empty
                Map.entry(snapshot(row(List.of("a.example.test,b.example.test"), chain, "AVAILABLE", null, null, 0)),
                        List.of("subjectAltNames", DOMAIN)),
                Map.entry(snapshot(row(List.of("a.example.test", ""), chain, "AVAILABLE", null, null, 0)), List.of("subjectAltNames", DOMAIN)),
                Map.entry(snapshot(row(List.of(" a.example.test"), chain, "AVAILABLE", null, null, 0)), List.of("subjectAltNames", DOMAIN)),
                // the store would fail to load the row
                Map.entry(snapshot(row(List.of("a.example.test", "a.example.test"), chain, "AVAILABLE", null, null, 0)),
                        List.of("Duplicate subjectAltNames", DOMAIN)),
                Map.entry(new StoreSnapshot(CONFIG, Map.of(" ", keyPair), null, null), List.of("domain", "keyPairs")),
                Map.entry(new StoreSnapshot(CONFIG, Map.of(" " + DOMAIN, keyPair), null, null), List.of("domain", "keyPairs")),
                // the ACME account key rows, whatever the provider suffix
                Map.entry(new StoreSnapshot(CONFIG, Map.of(HerdDBConfigurationStore.ACME_USER_KEY, keyPair), null, null),
                        List.of(HerdDBConfigurationStore.ACME_USER_KEY)),
                Map.entry(new StoreSnapshot(CONFIG, Map.of(HerdDBConfigurationStore.ACME_USER_KEY + "_other", keyPair), null, null),
                        List.of(HerdDBConfigurationStore.ACME_USER_KEY + "_other")),
                Map.entry(new StoreSnapshot(CONFIG, null, null, Map.of("", validRow())), List.of("domain", "certificates"))
        );
        for (final var invalid : cases) {
            final var err = assertThrows(invalid.getKey().toString(), ConfigurationNotValidException.class, invalid.getKey()::validate);
            for (final var part : invalid.getValue()) {
                assertThat(err.getMessage(), containsString(part));
            }
        }
    }

    @Test
    public void testToStringHidesKeyMaterial() {
        assertThat(new EncodedKeyPair("PRIV", "PUB").toString(), not(containsString("PRIV")));
        assertThat(validRow().toString(), not(containsString(validRow().chain())));
    }
}
