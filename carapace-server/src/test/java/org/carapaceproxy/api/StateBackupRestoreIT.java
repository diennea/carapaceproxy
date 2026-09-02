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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChainData;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.configstore.StoreNotReadyException;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RestoreInProgressException;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.carapaceproxy.utils.TestUserRealm;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.LoggerFactory;

/**
 * Test for {@code GET /api/config/backup} and {@code POST /api/config/restore}.
 */
class StateBackupRestoreIT extends UseAdminServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOMAIN = "app.example.test";
    private static final String OTHER_DOMAIN = "other.example.test";
    private static final String WAITING_DOMAIN = "waiting.example.test";

    private Properties databaseConfiguration() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", newFolder(tmpDir, "junit").getAbsolutePath());
        return configuration;
    }

    private HttpResponse backup(RawHttpClient client) throws Exception {
        final HttpResponse resp = client.get("/api/config/backup", credentials);
        assertThat(resp.isOk()).as(resp.getStatusLine()).isTrue();
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
        assertThat(resp.getStatusLine()).as(resp.getStatusLine()).contains("422");
        assertThat(resp.getBodyString()).contains(messagePart);
        assertThat(server.getDynamicConfigurationStore().exportState()).isEqualTo(before);
    }

    @Test
    void backupAndRestore() throws Exception {
        startServer(databaseConfiguration());
        final int port = TestUtils.getFreeNonEphemeralPort();
        final Properties dynamic = new Properties();
        dynamic.put("certificate.10.hostname", "*");
        dynamic.put("certificate.10.file", TestUtils.deployResource("ia.p12", tmpDir));
        dynamic.put("certificate.10.password", "changeit");
        // still waiting, no chain to write in the local store: the restore must not fail on it
        dynamic.put("certificate.11.hostname", WAITING_DOMAIN);
        dynamic.put("certificate.11.mode", "acme");
        dynamic.put("dynamiccertificatesmanager.localcertificates.store.path", newFolder(tmpDir, "junit").getAbsolutePath());
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
            assertThat(uploadCertificate(DOMAIN, null, createKeystore(chain, keyPair.getPrivate()), client, credentials).getBodyString()).contains("SUCCESS");

            final HttpResponse backup = backup(client);
            assertThat(header(backup, "Content-Disposition")).contains("attachment; filename=\"carapace-state-");
            assertThat(header(backup, "Cache-Control")).isEqualTo("no-store");
            final String archive = backup.getBodyString();
            final JsonNode json = MAPPER.readTree(archive);
            assertThat(json.get("format").asText()).isEqualTo("carapace-state");
            assertThat(json.get("formatVersion").asInt()).isOne();
            assertThat(json.get("acmeAccountKeys").has(DEFAULT_PROVIDER_NAME)).isTrue();
            final String originalConfig = client.get("/api/config", credentials).getBodyString();
            final StoreSnapshot originalState = server.getDynamicConfigurationStore().exportState();

            // change configuration, certificates and keys
            assertThat(uploadCertificate(OTHER_DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString()).contains("SUCCESS");
            assertThat(client.delete("/api/certificates/" + DOMAIN, credentials).isOk()).isTrue();
            server.getDynamicConfigurationStore().saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DOMAIN, true);
            assertThat(server.getDynamicConfigurationStore().exportState()).isNotEqualTo(originalState);

            final HttpResponse resp = restore(client, archive, null);
            assertThat(resp.isOk()).as(resp.getBodyString()).isTrue();
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertThat(result.get("ok").asBoolean()).isTrue();

            assertThat(client.get("/api/config", credentials).getBodyString()).isEqualTo(originalConfig);
            assertThat(server.getDynamicConfigurationStore().exportState()).isEqualTo(originalState);
            assertThat(server.getDynamicCertificatesManager().getCertificateDataForDomain(DOMAIN).getState()).isEqualTo(DynamicCertificateState.AVAILABLE);
            assertThat(server.getDynamicCertificatesManager().getCertificateDataForDomain(OTHER_DOMAIN)).isNull();

            assertThat(uploadCertificate(DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString()).contains("SUCCESS");
            assertThat(servedSerialNumber(port)).isNotEqualTo(backedUp.getSerialNumber());
            assertThat(restore(client, archive, null).isOk()).isTrue();
        }
        // served because every apply restarts the listeners, which build their SNI contexts when they bind: should an
        // apply stop restarting unchanged listeners (see #528), the restore must rebind them itself, and this test fails
        assertThat(servedSerialNumber(port)).isEqualTo(backedUp.getSerialNumber());
    }

    @Test
    void refusedRestores() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            assertThat(uploadCertificate(DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString()).contains("SUCCESS");
            final String archive = backup(client).getBodyString();
            // something to lose, should a refused restore write anyway
            assertThat(uploadCertificate(OTHER_DOMAIN, null, generateSampleChainData(), client, credentials).getBodyString()).contains("SUCCESS");

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
                    .put("backend.9.id", "secured")
                    .put("backend.9.enabled", "true")
                    .put("backend.9.ssl", "true")
                    .put("backend.9.cacertificate", "missing-ca.p12")
                    .put("backend.9.cacertificatepassword", "changeit")), null, "missing-ca.p12");
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.9.port", "1234")
                    .put("listener.9.forwarded", "preserve")), null, "preserve");

            final HttpResponse resp = restore(client, newer, "acceptNewerVersion=true");
            assertThat(resp.isOk()).as(resp.getBodyString()).isTrue();
            assertThat(server.getDynamicCertificatesManager().getCertificateDataForDomain(OTHER_DOMAIN)).isNull();
        }
    }

    @Test
    void duplicateListenersRestoredAsAnApplyTakesThem() throws Exception {
        startServer(databaseConfiguration());
        final int[] ports = new int[3];
        for (int i = 0; i < ports.length; i++) {
            ports[i] = TestUtils.getFreeNonEphemeralPort();
        }
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the default host is 0.0.0.0, and hosts compare ignoring case: an apply keeps the last one, a warning names them
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config"))
                    .put("listener.4.port", String.valueOf(ports[0]))
                    .put("listener.5.port", String.valueOf(ports[0]))
                    .put("listener.6.port", String.valueOf(ports[1]))
                    .put("listener.7.host", "0.0.0.0")
                    .put("listener.7.port", String.valueOf(ports[1]))
                    .put("listener.8.host", "localhost")
                    .put("listener.8.port", String.valueOf(ports[2]))
                    .put("listener.9.host", "LOCALHOST")
                    .put("listener.9.port", String.valueOf(ports[2])));
            final var logAppender = new ListAppender<ILoggingEvent>();
            logAppender.start();
            final var logger = (Logger) LoggerFactory.getLogger(HttpProxyServer.class);
            logger.addAppender(logAppender);
            try {
                final HttpResponse resp = restore(client, archive, null);
                assertThat(resp.isOk()).as(resp.getBodyString()).isTrue();
            } finally {
                logger.detachAppender(logAppender);
            }
            assertThat(logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).anySatisfy(arg -> assertThat(arg).contains("in conflict"));
            assertThat(server.getDynamicConfigurationStore().getProperty("listener.9.port", null)).isEqualTo(String.valueOf(ports[2]));
            // one listener for the same key, two for hosts differing only by case
            final List<NetworkListenerConfiguration> applied = server.getCurrentConfiguration().getListeners();
            assertThat(applied.stream().filter(listener -> listener.port() == ports[0]).count()).isOne();
            assertThat(applied.stream().filter(listener -> listener.port() == ports[1]).count()).isOne();
            assertThat(applied.stream().filter(listener -> listener.port() == ports[2]).count()).isEqualTo(2);
        }
    }

    @Test
    void restoredRealmMustAcceptTheCaller() throws Exception {
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
            assertThat(restore(client, archive, null).isOk()).isTrue();
        }
    }

    @Test
    void bootOnlyKeysNeverInTheArchive() throws Exception {
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
                assertThat(config.has(key)).as(key).isFalse();
            }
            assertThat(config.get("healthmanager.period").asText()).isEqualTo("20");
            assertThat(archive)
                    .doesNotContain("boot secret")
                    .doesNotContain("boot password");

            final var logAppender = new ListAppender<ILoggingEvent>();
            logAppender.start();
            final var logger = (Logger) LoggerFactory.getLogger(HttpProxyServer.class);
            logger.addAppender(logAppender);
            try {
                final HttpResponse resp = restore(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                        .put("aws.secretkey", "restored secret")
                        .put("db.admin.password", "restored password")), null);
                assertThat(resp.isOk()).as(resp.getBodyString()).isTrue();
            } finally {
                logger.detachAppender(logAppender);
            }
            // the warning names the dropped keys, never their values
            final List<String> loggedLines = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(loggedLines).anySatisfy(arg -> assertThat(arg).contains("[aws.secretkey, db.admin.password]"));
            for (final String line : loggedLines) {
                assertThat(line)
                        .doesNotContain("restored secret")
                        .doesNotContain("restored password");
            }
            final var stored = server.getDynamicConfigurationStore().exportState().config();
            assertThat(stored)
                    .doesNotContainKey("aws.secretkey")
                    .doesNotContainKey("db.admin.password")
                    .containsEntry("healthmanager.period", "20");
        }
    }

    @Test
    void writesAnswer503WhileARestoreRuns() throws Exception {
        startAdmin();
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the boot-only keys are all this configuration has
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25"));
            try (GroupMembershipHandler.RestoreSignal signal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                final HttpResponse write = client.post("/api/config/apply", null, "healthmanager.period=25", credentials);
                assertThat(write.getStatusLine()).as(write.getStatusLine()).contains("503");
                assertThat(header(write, "Retry-After")).isEqualTo(String.valueOf(RestoreInProgressFilter.RETRY_AFTER_SECONDS));
                // the restore passes the filter, then finds the signal taken
                final HttpResponse second = restore(client, archive, null);
                assertThat(second.getStatusLine()).as(second.getStatusLine()).contains("503");
                assertThat(header(second, "Retry-After")).isEqualTo(String.valueOf(RestoreInProgressFilter.RETRY_AFTER_SECONDS));
                // reads and backups pass
                assertThat(client.get("/api/config", credentials).isOk()).isTrue();
                backup(client);
            }
            final HttpResponse resp = restore(client, archive, null);
            assertThat(resp.isOk()).as(resp.getBodyString()).isTrue();
            // file mode: restored in memory only
            final JsonNode result = MAPPER.readTree(resp.getBodyString());
            assertThat(result.get("ok").asBoolean()).isTrue();
            assertThat(result.get("message").asText()).contains("restart");
            assertThat(server.getBackendHealthManager().getPeriod()).isEqualTo(25);
        }
    }

    @Test
    void restoreNotAppliedKeepsWritesPausedUntilReloaded() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // the boot-only keys are all this configuration has
            final String archive = edit(backup(client).getBodyString(), json -> ((ObjectNode) json.get("config")).put("healthmanager.period", "25"));
            {
                // valid when checked, but gone before the apply: stored, not applied (a failed listener bind would not
                // fail the apply here, every listener fails on its own)
                final File caFile = new File(TestUtils.deployResource("ca.p12", tmpDir));
                final String withBackendCa = edit(archive, json -> ((ObjectNode) json.get("config"))
                        .put("backend.1.id", "secured")
                        .put("backend.1.enabled", "true")
                        .put("backend.1.ssl", "true")
                        .put("backend.1.cacertificate", caFile.getAbsolutePath())
                        .put("backend.1.cacertificatepassword", "changeit"));
                final CompletableFuture<HttpResponse> restoring = CompletableFuture.supplyAsync(() -> {
                    try (RawHttpClient other = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
                        return restore(other, withBackendCa, null);
                    } catch (Exception err) {
                        throw new CompletionException(err);
                    }
                });
                // the restore waits a few seconds after raising its signal, well after its checks
                TestUtils.waitForCondition(server::isRestoreInProgress, 10);
                assertThat(caFile.delete()).isTrue();
                final HttpResponse resp = restoring.get(2, TimeUnit.MINUTES);
                assertThat(resp.getStatusLine()).as(resp.getStatusLine()).contains("500");
                assertThat(MAPPER.readTree(resp.getBodyString()).get("ok").asBoolean()).isFalse();
                // its own reload failed too: writes stay refused on this node
                assertThat(server.isRestoreInProgress()).isTrue();
                // and stay so after another restore that fails before its commit
                try (GroupMembershipHandler.RestoreSignal busySignal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                    final HttpResponse second = restore(client, archive, null);
                    assertThat(second.getStatusLine()).as(second.getStatusLine()).contains("503");
                }
                assertThat(server.isRestoreInProgress()).isTrue();
                final HttpResponse write = client.post("/api/config/apply", null, "healthmanager.period=25", credentials);
                assertThat(write.getStatusLine()).as(write.getStatusLine()).contains("503");
            }
            // a restore that applies clears the flag, through a reload of the stored state
            assertThat(restore(client, archive, null).isOk()).isTrue();
            TestUtils.waitForCondition(() -> !server.isRestoreInProgress(), 30);
        }
    }

    @Test
    void writeRefusedAtItsCommitIsReloadedAfterARestoreStoppedBeforeWriting() throws Exception {
        startServer(databaseConfiguration());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final int period = server.getBackendHealthManager().getPeriod();
            final String archive = backup(client).getBodyString();
            // a write past the API filter when the signal of a restore rises: applied here, refused at its commit
            try (GroupMembershipHandler.RestoreSignal signal = server.getGroupMembershipHandler().raiseRestoreSignal()) {
                final Properties changed = new Properties();
                changed.put("healthmanager.period", String.valueOf(period + 5));
                assertThatExceptionOfType(RestoreInProgressException.class).isThrownBy(() -> server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(changed)));
                assertThat(server.getBackendHealthManager().getPeriod()).isEqualTo(period + 5);
            }
            // the restore stops before writing: the node must not keep running the refused write
            assertThat(server.isRestoreInProgress()).isTrue();
            assertRefused(client, edit(archive, json -> ((ObjectNode) json.get("config"))
                    .put("listener.9.port", "1234")
                    .put("listener.9.forwarded", "preserve")), null, "preserve");
            TestUtils.waitForCondition(() -> !server.isRestoreInProgress(), 30);
            assertThat(server.getBackendHealthManager().getPeriod()).isEqualTo(period);
        }
    }

    @Test
    void restoreRefusedByAStoreNotWritableNeverRaisesTheSignal() throws Exception {
        final Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("admin.accesslog.path", File.createTempFile("junit", null, tmpDir).getAbsolutePath());
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
            assertThat(resp.getStatusLine()).as(resp.getStatusLine()).contains("503");
            assertThat(resp.getBodyString()).contains("replica down");
            assertThat(signalSeen.get()).isFalse();
            assertThat(server.isRestoreInProgress()).isFalse();
            assertThat(reloads.get()).isEqualTo(reloadsBefore);
            assertThat(server.getBackendHealthManager().getPeriod()).isNotEqualTo(25);
        }
    }

    private static BigInteger servedSerialNumber(int port) throws Exception {
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, DOMAIN)) {
            return ((X509Certificate) client.getServerCertificate()[0]).getSerialNumber();
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
