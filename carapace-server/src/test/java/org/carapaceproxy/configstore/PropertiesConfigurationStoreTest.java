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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.carapaceproxy.utils.TestUtils.assertEqualsKey;

import java.util.List;
import java.net.URI;
import java.security.KeyPair;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Unit tests for {@link PropertiesConfigurationStore}. These exercise the pure in-memory parsing,
 * index scanning and certificate/keypair/token storage that used to run as the "in-memory" branch of
 * ConfigurationStoreIT — no HerdDB, no temp files, no server.
 */
class PropertiesConfigurationStoreTest {

    private static final String d1 = "localhost1";
    private static final String d2 = "localhost2";

    private PropertiesConfigurationStore store;

    @AfterEach
    void after() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void parsesTypedProperties() throws Exception {
        Properties props = new Properties();
        props.setProperty("property.int.1", "    1    ");
        props.setProperty("property.int.2", "        ");
        props.setProperty("property.int.3", "   true     ");

        props.setProperty("property.long.1", "    1    ");
        props.setProperty("property.long.2", "        ");
        props.setProperty("property.long.3", "   true     ");

        props.setProperty("property.boolean.1", "    true    ");
        props.setProperty("property.boolean.2", "    false    ");
        props.setProperty("property.boolean.3", "    tr ue    ");
        props.setProperty("property.boolean.4", "        ");

        props.setProperty("property.string.1", "   a string     ");
        props.setProperty("property.string.2", "        ");

        props.setProperty("property.array.1", "   1,2,3,4     ");
        props.setProperty("property.array.2", "  a1,a2, a3   ,a4      ");
        props.setProperty("property.array.3", "        ");
        props.setProperty("property.array.4", "");

        String className = this.getClass().getName();
        props.setProperty("property.class.1", className);
        props.setProperty("property.class.2", "   " + className + "     ");
        props.setProperty("property.class.3", "        ");

        store = new PropertiesConfigurationStore(props);

        assertEquals(1, store.getInt("property.int.1", 11));
        assertEquals(11, store.getInt("property.int.2", 11)); // empty > default
        assertThrows(ConfigurationNotValidException.class, () -> store.getInt("property.int.3", 11));
        assertEquals(11, store.getInt("property.int.11", 11)); // not exists

        assertEquals(1L, store.getLong("property.long.1", 11));
        assertEquals(11L, store.getLong("property.long.2", 11)); // empty > default
        assertThrows(ConfigurationNotValidException.class, () -> store.getLong("property.long.3", 11));
        assertEquals(11L, store.getLong("property.long.11", 11)); // not exists

        assertEquals(true, store.getBoolean("property.boolean.1", false));
        assertEquals(false, store.getBoolean("property.boolean.2", true));
        assertEquals(false, store.getBoolean("property.boolean.3", true));
        assertEquals(true, store.getBoolean("property.boolean.4", true)); // empty > default
        assertEquals(true, store.getBoolean("property.boolean.ne", true)); // not exists

        assertEquals("a string", store.getString("property.string.1", "default"));
        assertNull(store.getString("property.string.2", null)); // empty > default
        assertEquals("default", store.getString("property.string.3", "default")); // not exists

        assertTrue(store.getValues("property.array.1", Set.of("default")).containsAll(List.of("1", "2", "3", "4")));
        assertTrue(store.getValues("property.array.2", Set.of("default")).containsAll(List.of("a1", "a2", "a3", "a4")));
        assertTrue(store.getValues("property.array.3", Set.of("default")).contains("default")); // no elements > default
        assertTrue(store.getValues("property.array.4", Set.of("default")).contains("default")); // empty > default
        assertTrue(store.getValues("property.array.11", Set.of("default")).contains("default")); // not exists

        String dClassName = Object.class.getName();
        assertEquals(className, store.getClassname("property.class.1", dClassName));
        assertEquals(className, store.getClassname("property.class.2", dClassName));
        assertEquals(dClassName, store.getClassname("property.class.3", dClassName)); // empty > default
        assertEquals(dClassName, store.getClassname("property.class.nd", dClassName)); // not defined > default
        assertNull(store.getClassname("property.class.nd", null)); // not defined > default
        assertThrows(ConfigurationNotValidException.class, () -> store.getClassname("property.class.4", "DClassName"));
    }

    @Test
    void findsMaxIndexForPrefix() throws Exception {
        store = new PropertiesConfigurationStore(new Properties());
        assertEquals(-1, store.findMaxIndexForPrefix("property"));

        Properties props = new Properties();
        props.setProperty("property.0.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(0, store.findMaxIndexForPrefix("property"));

        props = new Properties();
        props.setProperty("property.100.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(100, store.findMaxIndexForPrefix("property"));

        props = new Properties();
        props.setProperty("property.0.value", "value");
        props.setProperty("property.1.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(1, store.findMaxIndexForPrefix("property"));

        props.setProperty("property.100.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(100, store.findMaxIndexForPrefix("property"));

        props.setProperty("property2.111.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(100, store.findMaxIndexForPrefix("property"));
        assertEquals(111, store.findMaxIndexForPrefix("property2"));

        props.setProperty("property.weird.8.9.value", "value");
        store = new PropertiesConfigurationStore(props);
        assertEquals(100, store.findMaxIndexForPrefix("property"));
        assertEquals(8, store.findMaxIndexForPrefix("property.weird"));
        assertEquals(9, store.findMaxIndexForPrefix("property.weird.8"));
        assertEquals(-1, store.findMaxIndexForPrefix("property.weird.8.9"));
        assertEquals(-1, store.findMaxIndexForPrefix("property.weird.8.9.value"));
    }

    @Test
    void storesCertificatesKeyPairsAndTokens() throws Exception {
        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        store = new PropertiesConfigurationStore(props);

        assertEquals(4, store.asProperties(null).size());
        assertEquals(1, store.findMaxIndexForPrefix("certificate"));
        assertEquals(2, store.asProperties("certificate.1").size());
        assertTrue(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        ));
        assertFalse(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        ));

        // KeyPairs generation + saving
        KeyPair acmePair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveAcmeUserKey(acmePair, DEFAULT_PROVIDER_NAME);
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DEFAULT_PROVIDER_NAME); // key not overwritten

        // each provider has its own account key
        KeyPair customProviderPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveAcmeUserKey(customProviderPair, "customprovider");
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), "customprovider"); // key not overwritten

        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d1, true);
        KeyPair domain1Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain1Pair, d1, true); // key overwritten

        KeyPair domain2Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain2Pair, d2, false);
        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d2, false); // key not overwritten

        KeyPair loadedPair = store.loadAcmeUserKeyPair(DEFAULT_PROVIDER_NAME);
        assertEqualsKey(acmePair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(acmePair.getPublic(), loadedPair.getPublic());

        loadedPair = store.loadAcmeUserKeyPair("customprovider");
        assertEqualsKey(customProviderPair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(customProviderPair.getPublic(), loadedPair.getPublic());

        loadedPair = store.loadKeyPairForDomain(d1);
        assertEqualsKey(domain1Pair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(domain1Pair.getPublic(), loadedPair.getPublic());

        loadedPair = store.loadKeyPairForDomain(d2);
        assertEqualsKey(domain2Pair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(domain2Pair.getPublic(), loadedPair.getPublic());

        // Certificates saving/loading/updating
        CertificateData cert1 = new CertificateData(
                d1,
                Collections.emptySet(),
                "encodedChain1",
                DynamicCertificateState.AVAILABLE,
                URI.create("http://locallhost/order").toURL(),
                Map.of(d1, JSON.parse("{\"challenge\": \"data\"}"))
        );
        store.saveCertificate(cert1);

        CertificateData cert2 = new CertificateData(d2, "encodedChain2", DynamicCertificateState.WAITING);
        store.saveCertificate(cert2);

        assertEquals(cert1, store.loadCertificateForDomain(d1));
        assertEquals(cert2, store.loadCertificateForDomain(d2));

        cert1.setState(DynamicCertificateState.WAITING);
        cert1.setPendingOrderLocation(URI.create("http://locallhost/updatedorder").toURL());
        cert1.setPendingChallengesData(Map.of(cert1.getDomain(), JSON.parse("{\"challenge\": \"updateddata\"}")));
        store.saveCertificate(cert1);
        assertEquals(cert1, store.loadCertificateForDomain(d1));

        // ACME challenge tokens
        store.saveAcmeChallengeToken("token-id", "token-data");
        store.saveAcmeChallengeToken("token-id2", "token-data2");
        assertEquals("token-data", store.loadAcmeChallengeToken("token-id"));
        assertEquals("token-data2", store.loadAcmeChallengeToken("token-id2"));
        store.deleteAcmeChallengeToken("token-id");
        assertNull(store.loadAcmeChallengeToken("token-id"));
        store.deleteAcmeChallengeToken("token-id2");
        assertNull(store.loadAcmeChallengeToken("token-id2"));
    }
}
