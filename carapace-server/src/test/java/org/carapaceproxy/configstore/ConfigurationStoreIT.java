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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.carapaceproxy.utils.TestUtils.assertEqualsKey;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.carapaceproxy.api.ConfigResource;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.LoggerFactory;

/**
 * Test for {@link PropertiesConfigurationStore} and {@link HerdDBConfigurationStore}.
 *
 * @author paolo.venturi
 */
public class ConfigurationStoreIT {

    @TempDir
    public File tmpDir;

    private ConfigurationStore store;
    private String type;
    private static final String d1 = "localhost1";
    private static final String d2 = "localhost2";
    private static final String d3 = "localhost3";

    @AfterEach
    void after() {
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
                store = new HerdDBConfigurationStore(newStore, false, null, tmpDir, NullStatsLogger.INSTANCE);
            }
            store.commitConfiguration(newStore);
        } else {
            store = newStore;
        }
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"in-memory", "db"})
    void toStringConfigurationPreservesBackslashes(String type) throws Exception {
        this.type = type;

        final String domain = "(\\Qhost.example\\E|\\Qother.example\\E)";
        final String matcher = "regexp .*\\.example\\.com and header host web\\d+";
        Properties props = new Properties();
        props.setProperty("connectionpool.1.domain", domain);
        props.setProperty("route.5.match", matcher);
        updateConfigStore(props);

        // round-trip through the same parse path used by rewriteConfiguration and /api/config/apply
        ConfigurationStore reloaded = ConfigResource.buildStore(store.toStringConfiguration());
        assertThat(reloaded.getProperty("connectionpool.1.domain", null)).isEqualTo(domain);
        assertThat(reloaded.getProperty("route.5.match", null)).isEqualTo(matcher);

        // a second round-trip must be stable too
        ConfigurationStore reloadedTwice = ConfigResource.buildStore(reloaded.toStringConfiguration());
        assertThat(reloadedTwice.getProperty("connectionpool.1.domain", null)).isEqualTo(domain);
        assertThat(reloadedTwice.getProperty("route.5.match", null)).isEqualTo(matcher);
    }

    @Test
    void test() throws Exception {
        this.type = "db";

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

        assertThat(store.getInt("property.int.1", 11)).isOne();
        assertThat(store.getInt("property.int.2", 11)).isEqualTo(11); // empty > default
        assertThatThrownBy(() -> store.getInt("property.int.3", 11)).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("for parameter 'property.int.3'");
        assertThat(store.getInt("property.int.11", 11)).isEqualTo(11); // not exists

        assertThat(store.getLong("property.long.1", 11)).isOne();
        assertThat(store.getLong("property.long.2", 11)).isEqualTo(11L); // empty > default
        assertThatThrownBy(() -> store.getLong("property.long.3", 11)).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("for parameter 'property.long.3'");
        assertThat(store.getLong("property.long.11", 11)).isEqualTo(11L); // not exists

        assertThat(store.getBoolean("property.boolean.1", false)).isTrue();
        assertThat(store.getBoolean("property.boolean.2", true)).isFalse();
        assertThat(store.getBoolean("property.boolean.3", true)).isFalse();
        assertThat(store.getBoolean("property.boolean.4", true)).isTrue(); // empty > default
        assertThat(store.getBoolean("property.boolean.ne", true)).isTrue(); // not exists

        assertThat(store.getString("property.string.1", "default")).isEqualTo("a string");
        assertThat(store.getString("property.string.2", null)).isNull(); // empty > default
        assertThat(store.getString("property.string.3", "default")).isEqualTo("default"); // not exists

        assertThat(store.getValues("property.array.1", Set.of("default"))).contains("1", "2", "3", "4");
        assertThat(store.getValues("property.array.2", Set.of("default"))).contains("a1", "a2", "a3", "a4");
        assertThat(store.getValues("property.array.3", Set.of("default"))).contains("default"); // no elements > default
        assertThat(store.getValues("property.array.4", Set.of("default"))).contains("default"); // empty > default
        assertThat(store.getValues("property.array.11", Set.of("default"))).contains("default"); // not exitst

        String DClassName = Object.class.getName();
        assertThat(store.getClassname("property.class.1", DClassName)).isEqualTo(className);
        assertThat(store.getClassname("property.class.2", DClassName)).isEqualTo(className);
        assertThat(store.getClassname("property.class.3", DClassName)).isEqualTo(DClassName); // empty > default
        assertThat(store.getClassname("property.class.nd", DClassName)).isEqualTo(DClassName); // not defined > default
        assertThat(store.getClassname("property.class.nd", null)).isNull(); // not defined > default
        assertThatThrownBy(() -> store.getClassname("property.class.4", "DClassName")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("for parameter 'property.class.4'"); // not exists
    }

    @Test
    void propertiesIndex() throws Exception {
        this.type = "db";

        // test no properties -> max index -1
        updateConfigStore(new Properties());
        assertThat(store.findMaxIndexForPrefix("property")).isEqualTo(-1);

        // test single property -> max index == property index
        Properties props = new Properties();
        props.setProperty("property.0.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isZero();

        props = new Properties();
        props.setProperty("property.100.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isEqualTo(100);

        // test multiple properties -> max index == max property index
        props = new Properties();
        props.setProperty("property.0.value", "value");
        props.setProperty("property.1.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isOne();

        props.setProperty("property.100.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isEqualTo(100);

        props.setProperty("property2.111.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isEqualTo(100);
        assertThat(store.findMaxIndexForPrefix("property2")).isEqualTo(111);

        props.setProperty("property.weird.8.9.value", "value");
        updateConfigStore(props);
        assertThat(store.findMaxIndexForPrefix("property")).isEqualTo(100);
        assertThat(store.findMaxIndexForPrefix("property.weird")).isEqualTo(8);
        assertThat(store.findMaxIndexForPrefix("property.weird.8")).isEqualTo(9);
        assertThat(store.findMaxIndexForPrefix("property.weird.8.9")).isEqualTo(-1);
        assertThat(store.findMaxIndexForPrefix("property.weird.8.9.value")).isEqualTo(-1);
    }

    @Test
    void certiticatesConfigurationStore() throws Exception {
        this.type = "db";

        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        updateConfigStore(props);

        assertThat(store.asProperties(null)).hasSize(type.equals("db") ? 7 : 4);
        assertThat(store.findMaxIndexForPrefix("certificate")).isOne();
        assertThat(store.asProperties("certificate.1")).hasSize(2);
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        )).isTrue();
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        )).isFalse();

        testKeyPairOperations();
        testCertificateOperations();
        testAcmeChallengeTokens();
    }

    private void testKeyPairOperations() {
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
        assertThat(store.loadCertificateForDomain(d1)).isEqualTo(cert1);
        assertThat(store.loadCertificateForDomain(d2)).isEqualTo(cert2);

        // Cert Updating
        cert1.setState(DynamicCertificateState.WAITING);
        cert1.setPendingOrderLocation(URI.create("http://locallhost/updatedorder").toURL());
        cert1.setPendingChallengesData(Map.of(cert1.getDomain(), JSON.parse("{\"challenge\": \"updateddata\"}")));
        store.saveCertificate(cert1);
        assertThat(store.loadCertificateForDomain(d1)).isEqualTo(cert1);
    }

    private void testAcmeChallengeTokens() {
        store.saveAcmeChallengeToken("token-id", "token-data");
        store.saveAcmeChallengeToken("token-id2", "token-data2");
        assertThat(store.loadAcmeChallengeToken("token-id")).isEqualTo("token-data");
        assertThat(store.loadAcmeChallengeToken("token-id2")).isEqualTo("token-data2");
        // the CA hands back the token of a still-pending authorization on reorder
        store.saveAcmeChallengeToken("token-id", "token-data-new");
        assertThat(store.loadAcmeChallengeToken("token-id")).isEqualTo("token-data-new");
        assertThat(store.loadAcmeChallengeToken("token-id2")).isEqualTo("token-data2");
        store.deleteAcmeChallengeToken("token-id");
        assertThat(store.loadAcmeChallengeToken("token-id")).isNull();
        store.deleteAcmeChallengeToken("token-id2");
        assertThat(store.loadAcmeChallengeToken("token-id2")).isNull();
    }

    @Test
    void certificatesPersistency() throws Exception {
        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        props.put("db.jdbc.url", "jdbc:herddb:localhost");

        PropertiesConfigurationStore propertiesConfigurationStore = new PropertiesConfigurationStore(props);

        store = new HerdDBConfigurationStore(propertiesConfigurationStore, false, null, tmpDir, NullStatsLogger.INSTANCE);

        // Check applied configuration (loaded from empty db NB: passed configuration is ignored)
        assertThat(store.getProperty("certificate.0.hostname", "")).isEmpty();
        assertThat(store.getProperty("certificate.0.dynamic", "")).isEmpty();
        assertThat(store.getProperty("certificate.1.hostname", "")).isEmpty();
        assertThat(store.getProperty("certificate.1.dynamic", "")).isEmpty();

        // Apply first configuration
        store.commitConfiguration(propertiesConfigurationStore);

        // Check cached applied configuration
        assertThat(store.getProperty("certificate.0.hostname", "")).isEqualTo(d1);
        assertThat(store.getProperty("certificate.0.dynamic", "")).isEqualTo("true");
        assertThat(store.getProperty("certificate.1.hostname", "")).isEqualTo(d2);
        assertThat(store.getProperty("certificate.1.dynamic", "")).isEqualTo("true");

        assertThat(store.asProperties(null)).hasSize(5);
        assertThat(store.findMaxIndexForPrefix("certificate")).isOne();
        assertThat(store.asProperties("certificate.1")).hasSize(2);
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        )).isTrue();
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        )).isFalse();

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
        store = new HerdDBConfigurationStore(propertiesConfigurationStore, false, null, tmpDir, NullStatsLogger.INSTANCE);
        checkConfiguration();
    }

    @Test
    void customTablespace() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.tablespace", "carapace_test");
        props.setProperty("certificate.0.hostname", d1);
        PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

        store = openStore(staticConfiguration);
        store.commitConfiguration(staticConfiguration);
        assertThat(store.getProperty("certificate.0.hostname", "")).isEqualTo(d1);
        store.close();

        store = openStore(staticConfiguration);
        assertThat(store.getProperty("certificate.0.hostname", "")).isEqualTo(d1);
        final var snapshot = otherSnapshot();
        store.replaceState(snapshot, () -> {});
        store.close();

        store = openStore(staticConfiguration);
        assertThat(store.exportState()).isEqualTo(snapshot);
        store.close();

        // the default tablespace never saw that configuration nor the snapshot
        store = openStore(new PropertiesConfigurationStore(new Properties()));
        assertThat(store.exportState()).isEqualTo(new StoreSnapshot(null, null, null, null));
    }

    @Test
    void invalidTablespaceName() {
        Properties props = new Properties();
        props.setProperty("db.tablespace", "carapace test");
        PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

        assertThatExceptionOfType(ConfigurationStoreException.class).isThrownBy(() -> openStore(staticConfiguration))
                .withMessageContaining("Invalid db.tablespace \"carapace test\"");
    }

    @Test
    void datasourceClosedWhenBootFails() {
        Properties props = new Properties();
        // valid for us, too long for the metadata file HerdDB writes per tablespace
        String tableSpace = "a".repeat(300);
        props.setProperty("db.tablespace", tableSpace);

        ConfigurationStoreException err = assertThatExceptionOfType(ConfigurationStoreException.class).isThrownBy(() -> openStore(new PropertiesConfigurationStore(props))).actual();
        assertThat(err.getCause().getMessage()).contains(tableSpace);

        // the embedded server has been released
        store = openStore(new PropertiesConfigurationStore(new Properties()));
        assertThat(store.getProperty("certificate.0.hostname", "")).isEmpty();
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"in-memory", "db"})
    void exportReplaceRoundTrip(String type) throws Exception {
        this.type = type;
        populateStore();
        final var exported = store.exportState();

        assertThat(exported.config()).containsEntry("certificate.0.hostname", d1);
        assertThat(exported.keyPairs()).containsOnlyKeys(d1, d2);
        assertThat(exported.acmeAccountKeys()).containsOnlyKeys(DEFAULT_PROVIDER_NAME, "customprovider");
        final var row = exported.certificates().get(d1);
        assertThat(row.subjectAltNames()).containsExactly("www." + d1);
        assertThat(row.state()).isEqualTo("REQUEST_FAILED");
        assertThat(row.pendingOrder()).isEqualTo("http://localhost/order");
        assertThat(row.attemptCount()).isEqualTo(3);
        assertThat(row.message()).isEqualTo("failure");

        final var original = loadAll();
        store.close();
        store = openEmptyStore();
        store.replaceState(exported, () -> {});

        assertThat(store.exportState()).isEqualTo(exported);
        assertThat(loadAll()).containsExactlyInAnyOrderEntriesOf(original);
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"in-memory", "db"})
    void blankSubjectAltNamesAreNotExported(String type) throws Exception {
        this.type = type;
        final var props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        updateConfigStore(props);
        store.saveCertificate(new CertificateData(d1, Set.of("", " y." + d1 + ",x." + d1), null, DynamicCertificateState.WAITING, null, null));

        final var exported = store.exportState();
        assertThat(exported.certificates().get(d1).subjectAltNames()).containsExactly("x." + d1, "y." + d1);
        exported.validate();
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"in-memory", "db"})
    void replaceOverwritesEverything(String type) throws Exception {
        this.type = type;
        populateStore();
        store.saveAcmeChallengeToken("token-id", "token-data");

        final var snapshot = otherSnapshot();
        store.replaceState(snapshot, () -> {});

        assertThat(store.exportState()).isEqualTo(snapshot);
        assertThat(toMap(store.asProperties(null))).containsExactlyInAnyOrderEntriesOf(snapshot.config());
        assertThat(store.loadKeyPairForDomain(d1)).isNull();
        assertThat(store.loadCertificateForDomain(d1)).isNull();
        assertThat(ConfigurationStoreUtils.base64EncodeKey(store.loadAcmeUserKeyPair(DEFAULT_PROVIDER_NAME).getPrivate())).isEqualTo(snapshot.acmeAccountKeys().get(DEFAULT_PROVIDER_NAME).privateKey());
        assertThat(ConfigurationStoreUtils.base64EncodeKey(store.loadAcmeUserKeyPair("otherprovider").getPrivate())).isEqualTo(snapshot.acmeAccountKeys().get("otherprovider").privateKey());
        assertThat(store.loadAcmeUserKeyPair("customprovider")).isNull();
        assertThat(ConfigurationStoreUtils.base64EncodeKey(store.loadKeyPairForDomain(d3).getPrivate())).isEqualTo(snapshot.keyPairs().get(d3).privateKey());
        assertThat(store.loadCertificateForDomain(d3).getState()).isEqualTo(DynamicCertificateState.AVAILABLE);
        // tokens are not part of the state
        assertThat(store.loadAcmeChallengeToken("token-id")).isEqualTo("token-data");

        store.reload();
        assertThat(store.exportState()).isEqualTo(snapshot);
        assertThat(toMap(store.asProperties(null))).containsExactlyInAnyOrderEntriesOf(snapshot.config());
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"in-memory", "db"})
    void failingPreCommitCheckLeavesStateUntouched(String type) throws Exception {
        this.type = type;
        populateStore();
        final var before = store.exportState();
        final var loadedBefore = loadAll();

        final var err = assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> store.replaceState(otherSnapshot(), () -> {
                    throw new IllegalStateException("aborted");
                }))
                .actual();
        assertThat(err.getMessage()).isEqualTo("aborted");

        assertThat(store.exportState()).isEqualTo(before);
        assertThat(loadAll()).containsExactlyInAnyOrderEntriesOf(loadedBefore);
        store.reload();
        assertThat(store.exportState()).isEqualTo(before);
        assertThat(loadAll()).containsExactlyInAnyOrderEntriesOf(loadedBefore);
    }

    private void populateStore() throws Exception {
        Properties props = new Properties();
        props.setProperty("certificate.0.hostname", d1);
        props.setProperty("certificate.0.dynamic", "true");
        props.setProperty("certificate.1.hostname", d2);
        props.setProperty("certificate.1.dynamic", "true");
        updateConfigStore(props);

        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DEFAULT_PROVIDER_NAME);
        store.saveAcmeUserKey(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), "customprovider");
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
        final var otherAcmePair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        return new StoreSnapshot(
                Map.of("some.property", "value", "certificate.0.hostname", d3),
                Map.of(d3, encode(domainPair)),
                Map.of(DEFAULT_PROVIDER_NAME, encode(acmePair), "otherprovider", encode(otherAcmePair)),
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
        final var customAcmeKey = store.loadAcmeUserKeyPair("customprovider");
        return Map.of(
                "config", toMap(store.asProperties(null)),
                "acme", acmeKey == null ? "" : ConfigurationStoreUtils.base64EncodeKey(acmeKey.getPrivate()),
                "acme.custom", customAcmeKey == null ? "" : ConfigurationStoreUtils.base64EncodeKey(customAcmeKey.getPrivate()),
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
            return new HerdDBConfigurationStore(new PropertiesConfigurationStore(props), false, null, newFolder(tmpDir, "junit"), NullStatsLogger.INSTANCE);
        }
        return new PropertiesConfigurationStore(new Properties());
    }

    private HerdDBConfigurationStore openStore(ConfigurationStore staticConfiguration) {
        return new HerdDBConfigurationStore(staticConfiguration, false, null, tmpDir, NullStatsLogger.INSTANCE);
    }

    private void checkConfiguration() {
        // check new configuration has been applied successfully
        assertThat(store.getProperty("certificate.0.hostname", "")).isEmpty();
        assertThat(store.getProperty("certificate.0.dynamic", "")).isEmpty();
        assertThat(store.getProperty("certificate.1.hostname", "")).isEqualTo(d1);
        assertThat(store.getProperty("certificate.1.dynamic", "")).isEqualTo("false");
        assertThat(store.getProperty("certificate.3.hostname", "")).isEqualTo(d3);
        assertThat(store.getProperty("certificate.3.dynamic", "")).isEqualTo("true");

        assertThat(store.asProperties(null)).hasSize(4);
        assertThat(store.findMaxIndexForPrefix("certificate")).isEqualTo(3);
        assertThat(store.asProperties("certificate.1.")).hasSize(2);
        assertThat(store.asProperties("certificate.3.")).hasSize(2);
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d1)
        )).isTrue();
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(d3)
        )).isTrue();
        assertThat(store.anyPropertyMatches(
                (k, v) -> k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("unknown")
        )).isFalse();
    }

    @Test
    void commitConfigurationMasksSecretsInLogs() throws Exception {
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
            assertThat(loggedLines).anySatisfy(line -> assertThat(line).contains("acme.1.hmac", "******"));
            for (final var line : loggedLines) {
                assertThat(line)
                        .doesNotContain(hmac)
                        .doesNotContain(password);
            }

            // masking applies to the log only: the stored values stay raw, they are needed at use time
            assertThat(store.getProperty("acme.1.hmac", "")).isEqualTo(hmac);
            assertThat(store.getProperty("certificate.1.password", "")).isEqualTo(password);
        } finally {
            logger.detachAppender(logAppender);
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs) + "-" + System.nanoTime();
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
