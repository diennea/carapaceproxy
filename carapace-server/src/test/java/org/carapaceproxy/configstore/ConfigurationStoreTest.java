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
import static org.carapaceproxy.utils.TestUtils.assertEqualsKey;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.net.URI;
import java.security.KeyPair;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.TestUtils;
import org.hamcrest.core.IsNull;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Test for {@link PropertiesConfigurationStore} and {@link HerdDBConfigurationStore}.
 *
 * @author paolo.venturi
 */
@RunWith(JUnitParamsRunner.class)
public class ConfigurationStoreTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private ConfigurationStore store;
    private String type;
    private static final String d1 = "localhost1";
    private static final String d2 = "localhost2";
    private static final String d3 = "localhost3";

    @After
    public void after() {
        if (store != null) {
            store.close();
        }
    }

    private void updateConfigStore(Properties props) throws ConfigurationNotValidException {
        ConfigurationStore newStore = new PropertiesConfigurationStore(props);
        if (type.equals("db")) {
            if (store == null) {
                props.put("db.jdbc.url", "jdbc:herddb:localhost");
                props.put("db.admin.username", "theusername");
                props.put("db.admin.password", "thepassword");
                newStore = new PropertiesConfigurationStore(props);
                store = new HerdDBConfigurationStore(newStore, false, null, tmpDir.getRoot(), NullStatsLogger.INSTANCE);
            }
            store.commitConfiguration(newStore);
        } else {
            store = newStore;
        }
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void test(String type) throws Exception {
        this.type = type;

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

        updateConfigStore(props);

        assertThat(store.getInt("property.int.1", 11), is(1));
        assertThat(store.getInt("property.int.2", 11), is(11)); // empty > default
        TestUtils.assertThrows(ConfigurationNotValidException.class, () -> store.getInt("property.int.3", 11));
        assertThat(store.getInt("property.int.11", 11), is(11)); // not exists

        assertThat(store.getLong("property.long.1", 11), is(1L));
        assertThat(store.getLong("property.long.2", 11), is(11L)); // empty > default
        TestUtils.assertThrows(ConfigurationNotValidException.class, () -> store.getLong("property.long.3", 11));
        assertThat(store.getLong("property.long.11", 11), is(11L)); // not exists

        assertThat(store.getBoolean("property.boolean.1", false), is(true));
        assertThat(store.getBoolean("property.boolean.2", true), is(false));
        assertThat(store.getBoolean("property.boolean.3", true), is(false));
        assertThat(store.getBoolean("property.boolean.4", true), is(true)); // empty > default
        assertThat(store.getBoolean("property.boolean.ne", true), is(true)); // not exists

        assertThat(store.getString("property.string.1", "default"), is("a string"));
        assertThat(store.getString("property.string.2", null), is(IsNull.nullValue())); // empty > default
        assertThat(store.getString("property.string.3", "default"), is("default")); // not exists

        assertThat(store.getValues("property.array.1", Set.of("default")), hasItems("1", "2", "3", "4"));
        assertThat(store.getValues("property.array.2", Set.of("default")), hasItems("a1", "a2", "a3", "a4"));
        assertThat(store.getValues("property.array.3", Set.of("default")), hasItems("default")); // no elements > default
        assertThat(store.getValues("property.array.4", Set.of("default")), hasItems("default")); // empty > default
        assertThat(store.getValues("property.array.11", Set.of("default")), hasItems("default")); // not exitst

        String DClassName = Object.class.getName();
        assertThat(store.getClassname("property.class.1", DClassName), is(className));
        assertThat(store.getClassname("property.class.2", DClassName), is(className));
        assertThat(store.getClassname("property.class.3", DClassName), is(DClassName)); // empty > default
        assertThat(store.getClassname("property.class.nd", DClassName), is(DClassName)); // not defined > default
        assertThat(store.getClassname("property.class.nd", null), is(IsNull.nullValue())); // not defined > default
        TestUtils.assertThrows(ConfigurationNotValidException.class, () -> store.getClassname("property.class.4", "DClassName")); // not exists
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testPropertiesIndex(String type) throws Exception {
        this.type = type;

        // test no properties -> max index -1
        updateConfigStore(new Properties());
        assertThat(store.findMaxIndexForPrefix("property"), is(-1));

        // test single property -> max index == property index
        Properties props = new Properties();
        props.setProperty("property.0.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(0));

        props = new Properties();
        props.setProperty("property.100.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(100));

        // test multiple properties -> max index == max property index
        props = new Properties();
        props.setProperty("property.0.value", "value");
        props.setProperty("property.1.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(1));

        props.setProperty("property.100.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(100));

        props.setProperty("property2.111.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(100));
        assertThat(store.findMaxIndexForPrefix("property2"), is(111));

        props.setProperty("property.weird.8.9.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property"), is(100));
        assertThat(store.findMaxIndexForPrefix("property.weird"), is(8));
        assertThat(store.findMaxIndexForPrefix("property.weird.8"), is(9));
        assertThat(store.findMaxIndexForPrefix("property.weird.8.9"), is(-1));
        assertThat(store.findMaxIndexForPrefix("property.weird.8.9.value"), is(-1));
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testCertiticatesConfigurationStore(String type) throws Exception {
        this.type = type;

        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        updateConfigStore(props);

        assertEquals(type.equals("db") ? 7 : 4, store.asProperties(null).size());
        assertEquals(1, store.findMaxIndexForPrefix("certificate"));
        assertEquals(2, store.asProperties("certificate.1").size());
        assertEquals(true, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        ));
        assertEquals(false, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        ));

        testKeyPairOperations();
        testCertificateOperations();
        testAcmeChallengeTokens();
    }

    private void testKeyPairOperations() {
        // KeyPairs generation + saving
        KeyPair acmePair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveAcmeUserKey(acmePair);
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE)); // key not overwritten

        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d1, true);
        KeyPair domain1Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain1Pair, d1, true); // key overwritten

        KeyPair domain2Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain2Pair, d2, false);
        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d2, false); // key not overwritten

        // KeyPairs loading
        KeyPair loadedPair = store.loadAcmeUserKeyPair();
        assertEqualsKey(acmePair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(acmePair.getPublic(), loadedPair.getPublic());

        loadedPair = store.loadKeyPairForDomain(d1);
        assertEqualsKey(domain1Pair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(domain1Pair.getPublic(), loadedPair.getPublic());

        loadedPair = store.loadKeyPairForDomain(d2);
        assertEqualsKey(domain2Pair.getPrivate(), loadedPair.getPrivate());
        assertEqualsKey(domain2Pair.getPublic(), loadedPair.getPublic());
    }

    private void testCertificateOperations() throws Exception {
        // Certificates saving
        CertificateData cert1 = new CertificateData(
                d1,
                Collections.emptySet(),
                "encodedChain1",
                DynamicCertificateState.AVAILABLE,
                URI.create("http://locallhost/order").toURL(),
                Map.of(d1, JSON.parse("{\"challenge\": \"data\"}"))
        );
        store.saveCertificate(cert1);

        CertificateData cert2 = new CertificateData(
                d2, "encodedChain2", DynamicCertificateState.WAITING
        );
        store.saveCertificate(cert2);

        // Certificates loading
        assertEquals(cert1, store.loadCertificateForDomain(d1));
        assertEquals(cert2, store.loadCertificateForDomain(d2));

        // Cert Updating
        cert1.setState(DynamicCertificateState.WAITING);
        cert1.setPendingOrderLocation(URI.create("http://locallhost/updatedorder").toURL());
        cert1.setPendingChallengesData(Map.of(cert1.getDomain(), JSON.parse("{\"challenge\": \"updateddata\"}")));
        store.saveCertificate(cert1);
        assertEquals(cert1, store.loadCertificateForDomain(d1));
    }

    private void testAcmeChallengeTokens() {
        store.saveAcmeChallengeToken("token-id", "token-data");
        store.saveAcmeChallengeToken("token-id2", "token-data2");
        assertEquals("token-data", store.loadAcmeChallengeToken("token-id"));
        assertEquals("token-data2", store.loadAcmeChallengeToken("token-id2"));
        store.deleteAcmeChallengeToken("token-id");
        assertNull(store.loadAcmeChallengeToken("token-id"));
        store.deleteAcmeChallengeToken("token-id2");
        assertNull(store.loadAcmeChallengeToken("token-id2"));
    }

    @Test
    public void testCertificatesPersistency() throws ConfigurationNotValidException {
        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        props.put("db.jdbc.url", "jdbc:herddb:localhost");

        PropertiesConfigurationStore propertiesConfigurationStore = new PropertiesConfigurationStore(props);

        store = new HerdDBConfigurationStore(propertiesConfigurationStore, false, null, tmpDir.getRoot(), NullStatsLogger.INSTANCE);

        // Check applied configuration (loaded from empty db NB: passed configuration is ignored)
        assertEquals("", store.getProperty("certificate.0.hostname", ""));
        assertEquals("", store.getProperty("certificate.0.dynamic", ""));
        assertEquals("", store.getProperty("certificate.1.hostname", ""));
        assertEquals("", store.getProperty("certificate.1.dynamic", ""));

        // Apply first configuration
        store.commitConfiguration(propertiesConfigurationStore);

        // Check cached applied configuration
        assertEquals(d1, store.getProperty("certificate.0.hostname", ""));
        assertEquals("true", store.getProperty("certificate.0.dynamic", ""));
        assertEquals(d2, store.getProperty("certificate.1.hostname", ""));
        assertEquals("true", store.getProperty("certificate.1.dynamic", ""));

        assertEquals(5, store.asProperties(null).size());
        assertEquals(1, store.findMaxIndexForPrefix("certificate"));
        assertEquals(2, store.asProperties("certificate.1").size());
        assertEquals(true, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        ));
        assertEquals(false, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        ));

        // New configuration to apply
        props = new Properties();
        // no more certificate.0.*
        props.setProperty("certificate.1.hostname", d1); // changed from d2 ("localhost2")
        props.setProperty("certificate.1.dynamic", "false"); // changed from "true"
        props.setProperty("certificate.3.hostname", d3); // new
        props.setProperty("certificate.3.dynamic", "true"); // new
        propertiesConfigurationStore = new PropertiesConfigurationStore(props);

        store.commitConfiguration(propertiesConfigurationStore);

        // check new configuration has been applied successfully
        checkConfiguration();
        store.close();

        // Loading stored configuration
        props = new Properties();
        props.put("db.jdbc.url", "jdbc:herddb:localhost");
        propertiesConfigurationStore = new PropertiesConfigurationStore(props);
        store = new HerdDBConfigurationStore(propertiesConfigurationStore, false, null, tmpDir.getRoot(), NullStatsLogger.INSTANCE);
        checkConfiguration();
    }

    private void checkConfiguration() {
        // check new configuration has been applied successfully
        assertEquals("", store.getProperty("certificate.0.hostname", ""));
        assertEquals("", store.getProperty("certificate.0.dynamic", ""));
        assertEquals(d1, store.getProperty("certificate.1.hostname", ""));
        assertEquals("false", store.getProperty("certificate.1.dynamic", ""));
        assertEquals(d3, store.getProperty("certificate.3.hostname", ""));
        assertEquals("true", store.getProperty("certificate.3.dynamic", ""));

        assertEquals(4, store.asProperties(null).size());
        assertEquals(3, store.findMaxIndexForPrefix("certificate"));
        assertEquals(2, store.asProperties("certificate.1.").size());
        assertEquals(2, store.asProperties("certificate.3.").size());
        assertEquals(true, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        ));
        assertEquals(true, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d3)
        ));
        assertEquals(false, store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        ));
    }

}
