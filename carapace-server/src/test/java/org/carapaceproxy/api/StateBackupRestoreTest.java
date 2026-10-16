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
package org.carapaceproxy.api;

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChainData;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.carapaceproxy.utils.TestUserRealm;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Test for {@code GET /api/config/backup} and {@code POST /api/config/restore}.
 */
public class StateBackupRestoreTest extends UseAdminServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN = "app.example.test";
    private static final String OTHER_DOMAIN = "other.example.test";

    private Properties databaseConfiguration() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        return configuration;
    }

    private HttpResponse backup(RawHttpClient client) throws Exception {
        final HttpResponse resp = client.get("/api/config/backup", credentials);
        assertTrue(resp.getStatusLine(), resp.isOk());
        return resp;
    }

    private HttpResponse restore(RawHttpClient client, String archive, String params) throws Exception {
        // written as is: a duplicate key must reach the server
        return client.post("/api/config/restore", params, new RawValue(archive), credentials);
    }

    private static String edit(String archive, Consumer<ObjectNode> change) throws Exception {
        final ObjectNode json = (ObjectNode) MAPPER.readTree(archive);
        change.accept(json);
        return MAPPER.writeValueAsString(json);
    }

    private static String header(HttpResponse resp, String name) {
        return resp.getHeaderLines().stream()
                .filter(line -> line.toLowerCase().startsWith(name.toLowerCase() + ":"))
                .map(line -> line.substring(name.length() + 1).trim())
                .findFirst()
                .orElse(null);
    }

    /**
     * Refused restores must leave the store and its generation untouched.
     */
    private void assertRefused(RawHttpClient client, String archive, String params, String messagePart) throws Exception {
        final StoreSnapshot before = server.getDynamicConfigurationStore().exportState();
        final String generation = server.getDynamicConfigurationStore().readStateGeneration();
        final HttpResponse resp = restore(client, archive, params);
        assertTrue(resp.getStatusLine(), resp.getStatusLine().contains("422"));
        assertThat(resp.getBodyString(), containsString(messagePart));
        assertEquals(before, server.getDynamicConfigurationStore().exportState());
        assertEquals(generation, server.getDynamicConfigurationStore().readStateGeneration());
    }

    @Test
    public void testBackupAndRestore() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            assertTrue(uploadCertificate(DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));
            server.getDynamicConfigurationStore().saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DOMAIN, false);

            final HttpResponse backup = backup(client);
            assertThat(header(backup, "Content-Disposition"), containsString("attachment; filename=\"carapace-state-"));
            assertEquals("no-store", header(backup, "Cache-Control"));
            assertEquals("0", header(backup, ConfigResource.DOMAINS_WITHOUT_CERTIFICATE_COUNT_HEADER));
            assertNull(header(backup, ConfigResource.DOMAINS_WITHOUT_CERTIFICATE_HEADER));
            final String archive = backup.getBodyString();
            final JsonNode json = MAPPER.readTree(archive);
            assertEquals("carapace-state", json.get("format").asText());
            assertEquals(1, json.get("formatVersion").asInt());
            assertTrue(json.get("acmeAccountKeys").has(DEFAULT_PROVIDER_NAME));
            final String originalConfig = client.get("/api/config", credentials).getBodyString();
            final StoreSnapshot originalState = server.getDynamicConfigurationStore().exportState();
            final String originalGeneration = server.getDynamicConfigurationStore().readStateGeneration();

            // change configuration, certificates and keys
            assertTrue(uploadCertificate(OTHER_DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));
            assertTrue(client.delete("/api/certificates/" + DOMAIN, credentials).isOk());
            server.getDynamicConfigurationStore().saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DOMAIN, true);
            assertNotEquals(originalState, server.getDynamicConfigurationStore().exportState());

            final HttpResponse resp = restore(client, archive, null);
            assertTrue(resp.getBodyString(), resp.isOk());
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertTrue(result.get("ok").asBoolean());
            assertFalse(result.get("inMemoryOnly").asBoolean());

            assertEquals(originalConfig, client.get("/api/config", credentials).getBodyString());
            assertEquals(originalState, server.getDynamicConfigurationStore().exportState());
            assertNotEquals(originalGeneration, server.getDynamicConfigurationStore().readStateGeneration());
            assertEquals(DynamicCertificateState.AVAILABLE, server.getDynamicCertificatesManager().getCertificateDataForDomain(DOMAIN).getState());
            assertNull(server.getDynamicCertificatesManager().getCertificateDataForDomain(OTHER_DOMAIN));
        }
    }

    private Certificate[] uploadNewChain(RawHttpClient client) throws Exception {
        final KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        final Certificate[] chain = generateSampleChain(keyPair, false);
        final HttpResponse resp = uploadCertificate(DOMAIN, null, createKeystore(chain, keyPair.getPrivate()), client, credentials);
        assertTrue(resp.getBodyString(), resp.getBodyString().contains("SUCCESS"));
        return chain;
    }

    private Certificate servedLeaf() throws Exception {
        try (RawHttpClient client = new RawHttpClient("localhost", server.getLocalPort(), true, DOMAIN)) {
            return client.getServerCertificate()[0];
        }
    }

    /**
     * SSL listeners build their certificate contexts when they bind: a restore that only replaces the certificate
     * material, with the same certificate configuration, must still rebind them.
     */
    @Test
    public void testRestoreServesTheRestoredCertificate() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final Certificate[] chainA = uploadNewChain(client);
            final Properties dynamic = server.getDynamicConfigurationStore().asProperties(null);
            dynamic.put("listener.1.host", "localhost");
            dynamic.put("listener.1.port", "8443");
            dynamic.put("listener.1.ssl", "true");
            dynamic.put("listener.1.defaultcertificate", DOMAIN);
            changeDynamicConfiguration(dynamic);
            assertEquals(chainA[0], servedLeaf());

            final String archive = backup(client).getBodyString();
            final String config = client.get("/api/config", credentials).getBodyString();
            final Certificate[] chainB = uploadNewChain(client);
            assertNotEquals(chainA[0], chainB[0]);
            assertEquals(config, client.get("/api/config", credentials).getBodyString());
            // rebind explicitly, so that only the restore below depends on the restart under test
            server.getListeners().reloadCurrentConfiguration();
            assertEquals(chainB[0], servedLeaf());

            final HttpResponse resp = restore(client, archive, null);
            assertTrue(resp.getBodyString(), resp.isOk());
            assertEquals(chainA[0], servedLeaf());
        }
    }

    @Test
    public void testRefusedRestores() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            assertTrue(uploadCertificate(DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));
            final String archive = backup(client).getBodyString();
            // something to lose, should a refused restore write anyway
            assertTrue(uploadCertificate(OTHER_DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));

            assertRefused(client, edit(archive, json -> json.put("formatVersion", 2)), null, "formatVersion 2");
            assertRefused(client, "{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\",\"a\":\"c\"}}", null, "line 1, column");
            final String newer = edit(archive, json -> json.put("carapaceVersion", "99.0.0"));
            assertRefused(client, newer, null, "acceptNewerVersion");
            assertRefused(client, newer, "acceptNewerVersion=false", "acceptNewerVersion");
            assertRefused(client, edit(archive, json -> json.remove("carapaceVersion")), "acceptNewerVersion=true", "carapaceVersion");
            assertRefused(client, edit(archive, json -> json.remove("certificates")), null, "certificates");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("certificates").get(DOMAIN)).put("state", "SOMETHING_NEW")),
                    null, "SOMETHING_NEW");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("certificate.9.hostname", "*.wild.example.test")
                    .put("certificate.9.mode", "acme")), null, "AWS");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("certificate.9.hostname", "static.example.test")
                    .put("certificate.9.file", "missing.p12")), null, "missing.p12");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("backend.9.id", "secured")
                    .put("backend.9.enabled", "true")
                    .put("backend.9.ssl", "true")
                    .put("backend.9.cacertificate", "missing-ca.p12")
                    .put("backend.9.cacertificatepassword", "changeit")), null, "missing-ca.p12");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.9.port", "1234")
                    .put("listener.9.forwarded", "preserve")), null, "preserve");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.8.port", "1234")
                    .put("listener.9.port", "1234")), null, "1234");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.8.host", "localhost")
                    .put("listener.8.port", "1234")
                    .put("listener.9.host", "LocalHost")
                    .put("listener.9.port", "1234")), null, "1234");

            final HttpResponse resp = restore(client, newer, "acceptNewerVersion=true");
            assertTrue(resp.getBodyString(), resp.isOk());
            assertNull(server.getDynamicCertificatesManager().getCertificateDataForDomain(OTHER_DOMAIN));
        }
    }

    @Test
    public void testRestoredRealmMustAcceptTheCaller() throws Exception {
        final Properties configuration = databaseConfiguration();
        configuration.put("userrealm.class", TestUserRealm.class.getName());
        startServer(configuration);
        // the database starts empty: give the realm its user
        final Properties dynamic = new Properties();
        dynamic.put("user." + DEFAULT_USERNAME, DEFAULT_PASSWORD);
        changeDynamicConfiguration(dynamic);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final String archive = backup(client).getBodyString();
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("user." + DEFAULT_USERNAME, "another password")), null, "would not accept your credentials");
            assertTrue(restore(client, archive, null).isOk());
        }
    }

    @Test
    public void testFileModeRestoresInMemory() throws Exception {
        startAdmin();
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final String archive = backup(client).getBodyString();
            final HttpResponse resp = restore(client, edit(archive, json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25")), null);
            assertTrue(resp.getBodyString(), resp.isOk());
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertTrue(result.get("ok").asBoolean());
            assertTrue(result.get("inMemoryOnly").asBoolean());
            assertThat(result.get("message").asText(), containsString("restart"));
            assertEquals(25, server.getBackendHealthManager().getPeriod());
        }
    }

    @Test
    public void testBootOnlyKeysNeverInTheArchive() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("aws.secretkey", "boot secret");
        configuration.put("https.admin.sslcertfilepassword", "boot password");
        configuration.put("healthmanager.period", "20");
        startServer(configuration);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final String archive = backup(client).getBodyString();
            final JsonNode config = MAPPER.readTree(archive).get("config");
            for (final String key : List.of("http.admin.enabled", "http.admin.port", "http.admin.host", "userrealm.class",
                    "aws.secretkey", "https.admin.sslcertfilepassword")) {
                assertFalse(key, config.has(key));
            }
            assertEquals("20", config.get("healthmanager.period").asText());
            assertThat(archive, not(containsString("boot secret")));
            assertThat(archive, not(containsString("boot password")));

            final HttpResponse resp = restore(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("aws.secretkey", "x")
                    .put("db.admin.password", "y")), null);
            assertTrue(resp.getBodyString(), resp.isOk());
            final var stored = server.getDynamicConfigurationStore().exportState().config();
            assertFalse(stored.containsKey("aws.secretkey"));
            assertFalse(stored.containsKey("db.admin.password"));
            assertEquals("20", stored.get("healthmanager.period"));
        }
    }

    @Test
    public void testBackupReportsDomainsWithoutCertificate() throws Exception {
        startServer(databaseConfiguration());
        final Properties dynamic = new Properties();
        dynamic.put("certificate.1.hostname", DOMAIN);
        dynamic.put("certificate.1.mode", "acme");
        dynamic.put("certificate.2.hostname", OTHER_DOMAIN);
        dynamic.put("certificate.2.mode", "manual");
        changeDynamicConfiguration(dynamic);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final HttpResponse backup = backup(client);
            assertEquals("2", header(backup, ConfigResource.DOMAINS_WITHOUT_CERTIFICATE_COUNT_HEADER));
            assertEquals(DOMAIN + "," + OTHER_DOMAIN, header(backup, ConfigResource.DOMAINS_WITHOUT_CERTIFICATE_HEADER));
        }
    }

    @Test
    public void testRestoreWithLocalCertificatesStore() throws Exception {
        startServer(databaseConfiguration());
        final Properties dynamic = new Properties();
        dynamic.put("certificate.1.hostname", DOMAIN);
        dynamic.put("certificate.1.mode", "acme");
        dynamic.put("dynamiccertificatesmanager.localcertificates.store.path", tmpDir.newFolder().getAbsolutePath());
        changeDynamicConfiguration(dynamic);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final String archive = backup(client).getBodyString();
            // a certificate still waiting has no chain to write: the restore must not fail on it
            final HttpResponse resp = restore(client, edit(archive, json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25")), null);
            assertTrue(resp.getBodyString(), resp.isOk());
            assertTrue(MAPPER.readTree(resp.getBodyString()).get("ok").asBoolean());
            assertEquals(25, server.getBackendHealthManager().getPeriod());
        }
    }
}
