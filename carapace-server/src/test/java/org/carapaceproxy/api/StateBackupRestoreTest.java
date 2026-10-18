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
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChainData;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.configstore.StoreNotReadyException;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.RestoreInProgressException;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.carapaceproxy.utils.TestUserRealm;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Test for {@code GET /api/config/backup} and {@code POST /api/config/restore}.
 */
public class StateBackupRestoreTest extends UseAdminServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN = "app.example.test";
    private static final String OTHER_DOMAIN = "other.example.test";
    private static final String WAITING_DOMAIN = "waiting.example.test";

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
        final byte[] body = archive.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(("POST /api/config/restore" + (params == null ? "" : "?" + params) + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        request.write(body);
        return client.executeRequest(new ByteArrayInputStream(request.toByteArray()));
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
     * Refused restores must leave the store untouched.
     */
    private void assertRefused(RawHttpClient client, String archive, String params, String messagePart) throws Exception {
        final StoreSnapshot before = server.getDynamicConfigurationStore().exportState();
        final HttpResponse resp = restore(client, archive, params);
        assertTrue(resp.getStatusLine(), resp.getStatusLine().contains("422"));
        assertThat(resp.getBodyString(), containsString(messagePart));
        assertEquals(before, server.getDynamicConfigurationStore().exportState());
    }

    @Test
    public void testBackupAndRestore() throws Exception {
        startServer(databaseConfiguration());
        final int port = TestUtils.getFreeNonEphemeralPort();
        final Properties dynamic = new Properties();
        dynamic.put("certificate.10.hostname", "*");
        dynamic.put("certificate.10.file", TestUtils.deployResource("ia.p12", tmpDir.getRoot()));
        dynamic.put("certificate.10.password", "changeit");
        // still waiting, no chain to write in the local store: the restore must not fail on it
        dynamic.put("certificate.11.hostname", WAITING_DOMAIN);
        dynamic.put("certificate.11.mode", "acme");
        dynamic.put("dynamiccertificatesmanager.localcertificates.store.path", tmpDir.newFolder().getAbsolutePath());
        dynamic.put("listener.1.host", "localhost");
        dynamic.put("listener.1.port", String.valueOf(port));
        dynamic.put("listener.1.ssl", "true");
        dynamic.put("listener.1.defaultcertificate", "*");
        // not changeDynamicConfiguration: it adds the boot-only access log path, which the restore drops
        server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(dynamic));
        final KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        final Certificate[] chain = generateSampleChain(keyPair, false);
        final X509Certificate backedUp = (X509Certificate) chain[0];
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            assertTrue(uploadCertificate(DOMAIN, null, createKeystore(chain, keyPair.getPrivate()), client, credentials).getBodyString().contains("SUCCESS"));

            final HttpResponse backup = backup(client);
            assertThat(header(backup, "Content-Disposition"), containsString("attachment; filename=\"carapace-state-"));
            assertEquals("no-store", header(backup, "Cache-Control"));
            final String archive = backup.getBodyString();
            final JsonNode json = MAPPER.readTree(archive);
            assertEquals("carapace-state", json.get("format").asText());
            assertEquals(1, json.get("formatVersion").asInt());
            assertTrue(json.get("acmeAccountKeys").has(StoreSnapshot.LETSENCRYPT));
            final String originalConfig = client.get("/api/config", credentials).getBodyString();
            final StoreSnapshot originalState = server.getDynamicConfigurationStore().exportState();

            // change configuration, certificates and keys
            assertTrue(uploadCertificate(OTHER_DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));
            assertTrue(client.delete("/api/certificates/" + DOMAIN, credentials).isOk());
            server.getDynamicConfigurationStore().saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DOMAIN, true);
            assertNotEquals(originalState, server.getDynamicConfigurationStore().exportState());

            final HttpResponse resp = restore(client, archive, null);
            assertTrue(resp.getBodyString(), resp.isOk());
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertTrue(result.get("ok").asBoolean());

            assertEquals(originalConfig, client.get("/api/config", credentials).getBodyString());
            assertEquals(originalState, server.getDynamicConfigurationStore().exportState());
            assertEquals(DynamicCertificateState.AVAILABLE, server.getDynamicCertificatesManager().getCertificateDataForDomain(DOMAIN).getState());
            assertNull(server.getDynamicCertificatesManager().getCertificateDataForDomain(OTHER_DOMAIN));

            assertTrue(uploadCertificate(DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString().contains("SUCCESS"));
            assertNotEquals(backedUp.getSerialNumber(), servedSerialNumber(port));
            assertTrue(restore(client, archive, null).isOk());
        }
        // served because every apply drops the SNI cache of the listeners: should an apply stop reconfiguring them
        // (see #528), the restore must refresh them itself, and this test fails
        assertEquals(backedUp.getSerialNumber(), servedSerialNumber(port));
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
            final String newer = edit(archive, json -> json.put("carapaceVersion", "99.0.0"));
            assertRefused(client, newer, null, "acceptNewerVersion");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("certificate.9.hostname", "*.wild.example.test")
                    .put("certificate.9.mode", "acme")), null, "AWS");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("certificate.9.hostname", "static.example.test")
                    .put("certificate.9.file", "missing.p12")), null, "missing.p12");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("truststore.ssltruststorefile", "missing-truststore.p12")), null, "missing-truststore.p12");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.9.port", "1234")
                    .put("listener.9.forwarded", "preserve")), null, "preserve");
            // the default host is 0.0.0.0, and hosts compare ignoring case
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.8.port", "1235")
                    .put("listener.9.host", "0.0.0.0")
                    .put("listener.9.port", "1235")), null, "1235");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.8.host", "localhost")
                    .put("listener.8.port", "1236")
                    .put("listener.9.host", "LOCALHOST")
                    .put("listener.9.port", "1236")), null, "1236");

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
    public void testBootOnlyKeysNeverInTheArchive() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("aws.secretkey", "boot secret");
        configuration.put("https.admin.sslcertfilepassword", "boot password");
        configuration.put("healthmanager.period", "20");
        startServer(configuration);
        final List<String> logged = new CopyOnWriteArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                logged.add(new SimpleFormatter().formatMessage(logRecord));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger logger = Logger.getLogger("org.carapaceproxy");
        logger.addHandler(handler);
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
                    .put("aws.secretkey", "restored-aws-secret-value")
                    .put("db.admin.password", "restored-db-password-value")), null);
            assertTrue(resp.getBodyString(), resp.isOk());
            final var stored = server.getDynamicConfigurationStore().exportState().config();
            assertFalse(stored.containsKey("aws.secretkey"));
            assertFalse(stored.containsKey("db.admin.password"));
            assertEquals("20", stored.get("healthmanager.period"));
            // the dropped keys are named, their values never logged
            assertTrue(logged.toString(), logged.stream().anyMatch(line -> line.contains("aws.secretkey") && line.contains("db.admin.password")));
            for (final String line : logged) {
                assertThat(line, not(containsString("restored-aws-secret-value")));
                assertThat(line, not(containsString("restored-db-password-value")));
            }
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void testWritesAnswer503WhileARestoreRuns() throws Exception {
        startAdmin();
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the boot-only keys are all this configuration has
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25"));
            try (GroupMembershipHandler.RestoreSignal signal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                final HttpResponse write = client.post("/api/config/apply", null, "healthmanager.period=25", credentials);
                assertTrue(write.getStatusLine(), write.getStatusLine().contains("503"));
                assertEquals(String.valueOf(RestoreInProgressFilter.RETRY_AFTER_SECONDS), header(write, "Retry-After"));
                // the restore passes the filter, then finds the signal taken
                final HttpResponse second = restore(client, archive, null);
                assertTrue(second.getStatusLine(), second.getStatusLine().contains("503"));
                assertEquals(String.valueOf(RestoreInProgressFilter.RETRY_AFTER_SECONDS), header(second, "Retry-After"));
                // reads and backups pass
                assertTrue(client.get("/api/config", credentials).isOk());
                backup(client);
            }
            final HttpResponse resp = restore(client, archive, null);
            assertTrue(resp.getBodyString(), resp.isOk());
            // file mode: restored in memory only
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertTrue(result.get("ok").asBoolean());
            assertThat(result.get("message").asText(), containsString("restart"));
            assertEquals(25, server.getBackendHealthManager().getPeriod());
        }
    }

    @Test
    public void testRestoreNotAppliedKeepsWritesPausedUntilReloaded() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the boot-only keys are all this configuration has
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25"));
            try (ServerSocket busy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                // valid, but the listener cannot bind on this node: stored, not applied
                final String unbindable = edit(archive, json -> ((ObjectNode) json.get("config"))
                        .put("listener.1.host", busy.getInetAddress().getHostAddress())
                        .put("listener.1.port", String.valueOf(busy.getLocalPort())));
                final HttpResponse resp = restore(client, unbindable, null);
                assertTrue(resp.getStatusLine(), resp.getStatusLine().contains("500"));
                assertFalse(MAPPER.readTree(resp.getBodyString()).get("ok").asBoolean());
                // its own reload failed too: writes stay refused on this node
                assertTrue(server.isRestoreInProgress());
                // and stay so after another restore that fails before its commit
                try (GroupMembershipHandler.RestoreSignal busySignal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                    final HttpResponse second = restore(client, archive, null);
                    assertTrue(second.getStatusLine(), second.getStatusLine().contains("503"));
                }
                assertTrue(server.isRestoreInProgress());
                final HttpResponse write = client.post("/api/config/apply", null, "healthmanager.period=25", credentials);
                assertTrue(write.getStatusLine(), write.getStatusLine().contains("503"));
            }
            // a restore that applies clears the flag, through a reload of the stored state
            assertTrue(restore(client, archive, null).isOk());
            TestUtils.waitForCondition(() -> !server.isRestoreInProgress(), 30);
        }
    }

    @Test
    public void testWriteRefusedAtItsCommitIsReloadedAfterARestoreStoppedBeforeWriting() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final int period = server.getBackendHealthManager().getPeriod();
            final String archive = backup(client).getBodyString();
            // a write past the API filter when the signal of a restore rises: applied here, refused at its commit
            try (GroupMembershipHandler.RestoreSignal signal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                final Properties changed = new Properties();
                changed.put("healthmanager.period", String.valueOf(period + 5));
                assertThrows(RestoreInProgressException.class, () -> server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(changed)));
                assertEquals(period + 5, server.getBackendHealthManager().getPeriod());
            }
            // the restore stops before writing: the node must not keep running the refused write
            assertTrue(server.isRestoreInProgress());
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.9.port", "1234")
                    .put("listener.9.forwarded", "preserve")), null, "preserve");
            TestUtils.waitForCondition(() -> !server.isRestoreInProgress(), 30);
            assertEquals(period, server.getBackendHealthManager().getPeriod());
        }
    }

    @Test
    public void testRestoreRefusedByAStoreNotWritableNeverRaisesTheSignal() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("admin.accesslog.path", tmpDir.newFile().getAbsolutePath());
        configuration.put("userrealm.class", SimpleUserRealm.class.getName());
        final AtomicInteger reloads = new AtomicInteger();
        final AtomicBoolean writable = new AtomicBoolean(true);
        server.configureAtBoot(new PropertiesConfigurationStore(configuration) {
            @Override
            public void checkWritable() throws StoreNotReadyException {
                if (!writable.get()) {
                    throw new StoreNotReadyException("replica down");
                }
            }

            @Override
            public void reload() {
                reloads.incrementAndGet();
                super.reload();
            }
        });
        server.start();
        server.startAdminInterface();
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the boot-only keys are all this configuration has
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25"));
            writable.set(false);
            final int reloadsBefore = reloads.get();
            final AtomicBoolean signalSeen = new AtomicBoolean();
            final AtomicBoolean polling = new AtomicBoolean(true);
            final Thread poller = new Thread(() -> {
                while (polling.get()) {
                    if (server.isRestoreInProgress()) {
                        signalSeen.set(true);
                    }
                    Thread.onSpinWait();
                }
            });
            poller.start();
            final HttpResponse resp;
            try {
                resp = restore(client, archive, null);
            } finally {
                polling.set(false);
                poller.join();
            }
            assertTrue(resp.getStatusLine(), resp.getStatusLine().contains("503"));
            assertThat(resp.getBodyString(), containsString("replica down"));
            assertFalse(signalSeen.get());
            assertFalse(server.isRestoreInProgress());
            assertEquals(reloadsBefore, reloads.get());
            assertNotEquals(25, server.getBackendHealthManager().getPeriod());
        }
    }

    private static BigInteger servedSerialNumber(int port) throws Exception {
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, DOMAIN)) {
            return ((X509Certificate) client.getServerCertificate()[0]).getSerialNumber();
        }
    }
}
