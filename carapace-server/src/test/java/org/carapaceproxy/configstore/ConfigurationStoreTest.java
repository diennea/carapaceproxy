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
import static org.carapaceproxy.utils.TestUtils.assertEqualsKey;
import static org.carapaceproxy.utils.TestUtils.expectThrows;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import herddb.jdbc.HerdDBEmbeddedDataSource;
import herddb.server.ServerConfiguration;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.carapaceproxy.api.ConfigResource;
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
import org.slf4j.LoggerFactory;

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
    public void testToStringConfigurationPreservesBackslashes(String type) throws Exception {
        this.type = type;

        final String domain = "(\\Qhost.example\\E|\\Qother.example\\E)";
        final String matcher = "regexp .*\\.example\\.com and header host web\\d+";
        Properties props = new Properties();
        props.setProperty("connectionpool.1.domain", domain);
        props.setProperty("route.5.match", matcher);
        updateConfigStore(props);

        // round-trip through the same parse path used by rewriteConfiguration and /api/config/apply
        ConfigurationStore reloaded = ConfigResource.buildStore(store.toStringConfiguration());
        assertThat(reloaded.getProperty("connectionpool.1.domain", null), is(domain));
        assertThat(reloaded.getProperty("route.5.match", null), is(matcher));

        // a second round-trip must be stable too
        ConfigurationStore reloadedTwice = ConfigResource.buildStore(reloaded.toStringConfiguration());
        assertThat(reloadedTwice.getProperty("connectionpool.1.domain", null), is(domain));
        assertThat(reloadedTwice.getProperty("route.5.match", null), is(matcher));
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
        store.saveAcmeUserKey(acmePair, DEFAULT_PROVIDER_NAME);
        // key isn't overwritten
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DEFAULT_PROVIDER_NAME);

        // each provider has its own account key
        KeyPair customProviderPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveAcmeUserKey(customProviderPair, "customprovider");
        // key isn't overwritten
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), "customprovider");

        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d1, true);
        KeyPair domain1Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain1Pair, d1, true); // key overwritten

        KeyPair domain2Pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(domain2Pair, d2, false);
        // key isn't overwritten
        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d2, false);

        // KeyPairs loading
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

    @Test
    public void testCustomTablespace() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.tablespace", "carapace_test");
        props.setProperty("certificate.0.hostname", d1);
        PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

        store = openStore(staticConfiguration);
        store.commitConfiguration(staticConfiguration);
        assertEquals(d1, store.getProperty("certificate.0.hostname", ""));
        store.close();

        store = openStore(staticConfiguration);
        assertEquals(d1, store.getProperty("certificate.0.hostname", ""));
        final var snapshot = otherSnapshot();
        store.replaceState(snapshot, () -> {});
        store.close();

        store = openStore(staticConfiguration);
        assertEquals(snapshot, store.exportState());
        store.close();

        // the default tablespace never saw that configuration nor the snapshot
        store = openStore(new PropertiesConfigurationStore(new Properties()));
        assertEquals(new StoreSnapshot(null, null, null, null), store.exportState());
    }

    @Test
    public void testInvalidTablespaceName() {
        Properties props = new Properties();
        props.setProperty("db.tablespace", "carapace test");
        PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

        assertThrows(ConfigurationStoreException.class, () -> openStore(staticConfiguration));
    }

    @Test
    public void testDatasourceClosedWhenBootFails() {
        Properties props = new Properties();
        // valid for us, too long for the metadata file HerdDB writes per tablespace
        String tableSpace = "a".repeat(300);
        props.setProperty("db.tablespace", tableSpace);

        ConfigurationStoreException err = assertThrows(
                ConfigurationStoreException.class, () -> openStore(new PropertiesConfigurationStore(props))
        );
        assertThat(err.getCause().getMessage(), containsString(tableSpace));

        // the embedded server has been released
        store = openStore(new PropertiesConfigurationStore(new Properties()));
        assertEquals("", store.getProperty("certificate.0.hostname", ""));
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testExportReplaceRoundTrip(String type) throws Exception {
        this.type = type;
        populateStore();
        final var exported = store.exportState();

        assertEquals(d1, exported.config().get("certificate.0.hostname"));
        assertEquals(Set.of(d1, d2), exported.keyPairs().keySet());
        assertEquals(Set.of(DEFAULT_PROVIDER_NAME, "zerossl"), exported.acmeAccountKeys().keySet());
        final var row = exported.certificates().get(d1);
        assertEquals(List.of("www." + d1), row.subjectAltNames());
        assertEquals("REQUEST_FAILED", row.state());
        assertEquals("http://localhost/order", row.pendingOrder());
        assertEquals(3, row.attemptCount());
        assertEquals("failure", row.message());

        final var original = loadAll();
        store.close();
        store = openEmptyStore();
        store.replaceState(exported, () -> {});

        assertEquals(exported, store.exportState());
        assertEquals(original, loadAll());
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testBlankSubjectAltNamesAreNotExported(String type) throws Exception {
        this.type = type;
        final var props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        updateConfigStore(props);
        store.saveCertificate(new CertificateData(d1, Set.of("", " y." + d1 + ",x." + d1), null, DynamicCertificateState.WAITING, null, null));

        final var exported = store.exportState();
        assertEquals(List.of("x." + d1, "y." + d1), exported.certificates().get(d1).subjectAltNames());
        exported.validate();
    }

    @Test
    public void testBlankSubjectAltNamesAlreadyStoredAreNotExported() throws Exception {
        this.type = "db";
        final var config = new Properties();
        config.setProperty("certificate.0.hostname", d1);
        updateConfigStore(config);
        store.saveCertificate(new CertificateData(d1, Set.of("x." + d1), null, DynamicCertificateState.WAITING, null, null));
        store.close();
        store = null;

        // a row saved before blank names were dropped on save
        final var props = new Properties();
        props.setProperty(ServerConfiguration.PROPERTY_BASEDIR, tmpDir.getRoot().getAbsolutePath());
        try (var datasource = new HerdDBEmbeddedDataSource(props);
             var con = datasource.getConnection();
             var ps = con.prepareStatement("UPDATE digital_certificates SET subjectAltNames=? WHERE domain=?")) {
            ps.setString(1, ",x." + d1);
            ps.setString(2, d1);
            assertEquals(1, ps.executeUpdate());
        }

        store = openStore(new PropertiesConfigurationStore(new Properties()));
        final var exported = store.exportState();
        assertEquals(List.of("x." + d1), exported.certificates().get(d1).subjectAltNames());
        exported.validate();
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testReplaceOverwritesEverything(String type) throws Exception {
        this.type = type;
        populateStore();
        store.saveAcmeChallengeToken("token-id", "token-data");

        final var snapshot = otherSnapshot();
        store.replaceState(snapshot, () -> {});

        assertEquals(snapshot, store.exportState());
        assertEquals(snapshot.config(), toMap(store.asProperties(null)));
        assertNull(store.loadKeyPairForDomain(d1));
        assertNull(store.loadCertificateForDomain(d1));
        assertEquals(snapshot.acmeAccountKeys().get(DEFAULT_PROVIDER_NAME).privateKey(),
                ConfigurationStoreUtils.base64EncodeKey(store.loadAcmeUserKeyPair(DEFAULT_PROVIDER_NAME).getPrivate()));
        assertEquals(snapshot.acmeAccountKeys().get("custom").privateKey(),
                ConfigurationStoreUtils.base64EncodeKey(store.loadAcmeUserKeyPair("custom").getPrivate()));
        assertNull(store.loadAcmeUserKeyPair("zerossl"));
        assertEquals(snapshot.keyPairs().get(d3).privateKey(),
                ConfigurationStoreUtils.base64EncodeKey(store.loadKeyPairForDomain(d3).getPrivate()));
        assertEquals(DynamicCertificateState.AVAILABLE, store.loadCertificateForDomain(d3).getState());
        // tokens are not part of the state
        assertEquals("token-data", store.loadAcmeChallengeToken("token-id"));

        store.reload();
        assertEquals(snapshot, store.exportState());
        assertEquals(snapshot.config(), toMap(store.asProperties(null)));
    }

    @Test
    @Parameters({"in-memory", "db"})
    public void testFailingPreCommitCheckLeavesStateUntouched(String type) throws Exception {
        this.type = type;
        populateStore();
        final var before = store.exportState();
        final var loadedBefore = loadAll();

        final var err = expectThrows(IllegalStateException.class,
                () -> store.replaceState(otherSnapshot(), () -> {
                    throw new IllegalStateException("lost the lock");
                }));
        assertEquals("lost the lock", err.getMessage());

        assertEquals(before, store.exportState());
        assertEquals(loadedBefore, loadAll());
        store.reload();
        assertEquals(before, store.exportState());
        assertEquals(loadedBefore, loadAll());
    }

    @Test
    public void testNullConfigValueFailsBeforeCommit() throws Exception {
        this.type = "db";
        populateStore();
        final var before = store.exportState();
        final var loadedBefore = loadAll();
        final var other = otherSnapshot();
        final Map<String, String> config = new HashMap<>(other.config());
        config.put("null.property", null);
        final var snapshot = new StoreSnapshot(config, other.keyPairs(), other.acmeAccountKeys(), other.certificates());

        assertThrows(NullPointerException.class, () -> store.replaceState(snapshot, () -> {}));

        assertEquals(before, store.exportState());
        assertEquals(loadedBefore, loadAll());
        store.reload();
        assertEquals(before, store.exportState());
        assertEquals(loadedBefore, loadAll());
    }

    private void populateStore() throws Exception {
        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        updateConfigStore(props);

        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DEFAULT_PROVIDER_NAME);
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), "zerossl");
        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d1, false);
        store.saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), d2, false);
        store.saveCertificate(new CertificateData(
                d1,
                Set.of("www." + d1),
                "encodedChain1",
                DynamicCertificateState.REQUEST_FAILED,
                URI.create("http://localhost/order").toURL(),
                Map.of(d1, JSON.parse("{\"challenge\": \"data\"}")),
                3,
                "failure"
        ));
        store.saveCertificate(new CertificateData(d2, "encodedChain2", DynamicCertificateState.AVAILABLE));
    }

    private StoreSnapshot otherSnapshot() throws IOException {
        final var domainPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        final var acmePair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        return new StoreSnapshot(
                Map.of("some.property", "value", "certificate.0.hostname", d3),
                Map.of(d3, encode(domainPair)),
                Map.of(DEFAULT_PROVIDER_NAME, encode(acmePair), "custom", encode(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE))),
                Map.of(d3, new StoreSnapshot.CertificateRow(
                        List.of("a." + d3, "b." + d3),
                        "encodedChain3",
                        "AVAILABLE",
                        "http://localhost/order3",
                        ConfigurationStoreUtils.formatChallengesData(Map.of(d3, JSON.parse("{\"challenge\": \"data3\"}"))),
                        1,
                        "message"
                ))
        );
    }

    private static StoreSnapshot.EncodedKeyPair encode(KeyPair pair) {
        return new StoreSnapshot.EncodedKeyPair(
                ConfigurationStoreUtils.base64EncodeKey(pair.getPrivate()),
                ConfigurationStoreUtils.base64EncodeKey(pair.getPublic())
        );
    }

    /**
     * Reads the state through the regular accessors, so that the round trip is checked on what the server sees.
     */
    private Map<String, Object> loadAll() {
        final var acmeKey = store.loadAcmeUserKeyPair(DEFAULT_PROVIDER_NAME);
        final var zerosslKey = store.loadAcmeUserKeyPair("zerossl");
        return Map.of(
                "config", toMap(store.asProperties(null)),
                "acme", acmeKey == null ? "" : ConfigurationStoreUtils.base64EncodeKey(acmeKey.getPrivate()),
                "acme.zerossl", zerosslKey == null ? "" : ConfigurationStoreUtils.base64EncodeKey(zerosslKey.getPrivate()),
                "key1", ConfigurationStoreUtils.base64EncodeKey(store.loadKeyPairForDomain(d1).getPrivate()),
                "key2", ConfigurationStoreUtils.base64EncodeKey(store.loadKeyPairForDomain(d2).getPublic()),
                "cert1", store.loadCertificateForDomain(d1),
                "cert1.sans", store.loadCertificateForDomain(d1).getSubjectAltNames(),
                "cert1.challenges", String.valueOf(store.loadCertificateForDomain(d1).getPendingChallengesData()),
                "cert2", store.loadCertificateForDomain(d2)
        );
    }

    private static Map<String, String> toMap(Properties properties) {
        final Map<String, String> map = new HashMap<>();
        properties.forEach((k, v) -> map.put(k.toString(), v.toString()));
        return map;
    }

    private ConfigurationStore openEmptyStore() throws IOException {
        if (type.equals("db")) {
            Properties props = new Properties();
            props.put("db.jdbc.url", "jdbc:herddb:localhost");
            return new HerdDBConfigurationStore(new PropertiesConfigurationStore(props), false, null, tmpDir.newFolder(), NullStatsLogger.INSTANCE);
        }
        return new PropertiesConfigurationStore(new Properties());
    }

    private HerdDBConfigurationStore openStore(ConfigurationStore staticConfiguration) {
        return new HerdDBConfigurationStore(staticConfiguration, false, null, tmpDir.getRoot(), NullStatsLogger.INSTANCE);
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

    @Test
    public void testCommitConfigurationMasksSecretsInLogs() throws Exception {
        this.type = "db";
        final var logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        final var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HerdDBConfigurationStore.class);
        logger.addAppender(logAppender);
        try {
            final var hmac = "very-secret-mac-key";
            final var password = "very-secret-password";
            Properties props = new Properties();
            props.setProperty("acme.1.name", "digicert");
            props.setProperty("acme.1.url", "https://acme.example.com/directory");
            props.setProperty("acme.1.kid", "my-kid");
            props.setProperty("acme.1.hmac", hmac);
            props.setProperty("certificate.1.password", password);
            updateConfigStore(props);

            final var loggedLines = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(loggedLines, hasItems(containsString("acme.1.hmac"), containsString("******")));
            for (final var line : loggedLines) {
                assertThat(line, not(containsString(hmac)));
                assertThat(line, not(containsString(password)));
            }

            // masking applies to the log only: the stored values stay raw, they are needed at use time
            assertEquals(hmac, store.getProperty("acme.1.hmac", ""));
            assertEquals(password, store.getProperty("certificate.1.password", ""));
        } finally {
            logger.detachAppender(logAppender);
        }
    }

}
