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

import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.configstore.StoreSnapshot.CertificateRow;
import org.carapaceproxy.configstore.StoreSnapshot.EncodedKeyPair;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.CarapaceVersion;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * Test for {@link StateArchive}: format checks, version rule and round trip.
 */
public class StateArchiveTest {

    private static final EncodedKeyPair PAIR = new EncodedKeyPair("private", "public");
    private static final StoreSnapshot SNAPSHOT = new StoreSnapshot(
            Map.of("certificate.0.hostname", "app.example.test", "certificate.0.mode", "acme"),
            Map.of("app.example.test", PAIR),
            Map.of(DEFAULT_PROVIDER_NAME, PAIR),
            Map.of("app.example.test", new CertificateRow(List.of("www.app.example.test"), "chain", "AVAILABLE", null, null, 0, null))
    );

    private static StateArchive read(String json) throws ConfigurationNotValidException {
        return StateArchive.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static StateArchive archive(String carapaceVersion) {
        return new StateArchive(StateArchive.FORMAT, 1, carapaceVersion, "abc1234", "2026-01-01T00:00:00Z",
                SNAPSHOT.config(), SNAPSHOT.keyPairs(), SNAPSHOT.acmeAccountKeys(), SNAPSHOT.certificates());
    }

    private void assertAccepted(String archiveVersion, String runtimeVersion, boolean acceptNewer) throws Exception {
        assertEquals(archiveVersion + " on " + runtimeVersion, SNAPSHOT, archive(archiveVersion).toSnapshot(runtimeVersion, acceptNewer));
    }

    @Test
    public void testVersionRule() throws Exception {
        assertAccepted("2.1.3", "2.1.4", false);
        assertAccepted("2.0.9", "2.1.0", false);
        assertAccepted("2.1.4", "2.1.4", false);
        assertAccepted("2.1.4-SNAPSHOT", "2.1.4", false);
        assertAccepted("2.1.4", "2.1.4-SNAPSHOT", false);
        assertAccepted("10.0.0", "9.9.9", true);
        assertAccepted("2.1.4", "dev", false);
        assertAccepted("dev", "2.1.4", false);
        assertAccepted("not a version", "dev", false);
        assertThrows(ConfigurationNotValidException.class, () -> archive(null).toSnapshot("2.1.4", false));
        assertThrows(ConfigurationNotValidException.class, () -> archive("not a version").toSnapshot("2.1.4", true));
        // too long to be a version number: not read as 2.1.123456789
        assertThrows(ConfigurationNotValidException.class, () -> archive("2.1.1234567890").toSnapshot("2.1.4", false));

        final var err = assertThrows(ConfigurationNotValidException.class, () -> archive("2.3.0").toSnapshot("2.1.4", false));
        assertThat(err.getMessage(), containsString("2.3.0"));
        assertThat(err.getMessage(), containsString("acceptNewerVersion"));
        assertThrows(ConfigurationNotValidException.class, () -> archive("2.1.5-SNAPSHOT").toSnapshot("2.1.4", false));
        assertThrows(ConfigurationNotValidException.class, () -> archive("10.0.0").toSnapshot("9.9.9", false));
    }

    @Test
    public void testUnusedAcmeAccountKeysAccepted() throws Exception {
        final var config = Map.of("certificate.0.hostname", "app.example.test", "acme.0.name", "other", "acme.0.url", "acme://pebble");
        final var keys = Map.of(DEFAULT_PROVIDER_NAME, PAIR, "other", PAIR, "unused", PAIR);
        final var archive = new StateArchive(StateArchive.FORMAT, 1, "2.1.4", "abc1234", "2026-01-01T00:00:00Z", config,
                SNAPSHOT.keyPairs(), keys, SNAPSHOT.certificates());
        final var logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        final var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StateArchive.class);
        logger.addAppender(logAppender);
        try {
            // only reported with a warning: the store keeps every account key
            assertEquals(keys, archive.toSnapshot("2.1.4", false).acmeAccountKeys());
        } finally {
            logger.detachAppender(logAppender);
        }
        final var warnings = logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(1, warnings.stream().filter(warning -> warning.contains("unused")).count());
        assertTrue(warnings.stream().noneMatch(warning -> warning.contains("other") || warning.contains(DEFAULT_PROVIDER_NAME)));
    }

    @Test
    public void testFormatChecks() {
        final var version2 = assertThrows(ConfigurationNotValidException.class,
                () -> read("{\"format\":\"carapace-state\",\"formatVersion\":2,\"config\":{\"a\":\"b\"}}"));
        assertThat(version2.getMessage(), containsString("formatVersion 2"));
        assertThrows(ConfigurationNotValidException.class, () -> read("{\"format\":\"carapace-state\",\"config\":{\"a\":\"b\"}}"));
        assertThrows(ConfigurationNotValidException.class, () -> read("{\"format\":\"other\",\"formatVersion\":1}"));
        assertThrows(ConfigurationNotValidException.class, () -> read("{\"formatVersion\":1}"));
        assertThrows(ConfigurationNotValidException.class, () -> read("not json"));
        assertThrows(ConfigurationNotValidException.class, () -> read(""));
        assertThrows(ConfigurationNotValidException.class, () -> read("null"));
        final var duplicate = assertThrows(ConfigurationNotValidException.class,
                () -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\",\"a\":\"c\"}}"));
        assertThat(duplicate.getMessage(), containsString("line 1, column"));
        // the parser would quote the token: an unquoted key must not reach the message, nor the logs
        final var leak = assertThrows(ConfigurationNotValidException.class,
                () -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"keyPairs\":{\"d.test\":{\"privateKey\":MIIEvQIBADANBg}}}"));
        assertThat(leak.getMessage(), not(containsString("MIIEvQ")));
        assertThat(leak.getMessage(), containsString("line 1, column"));
        assertThrows(ConfigurationNotValidException.class,
                () -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\"}}SECRET"));
        final var missing = assertThrows(ConfigurationNotValidException.class,
                () -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\"},\"keyPairs\":{},\"acmeAccountKeys\":{}}"));
        assertThat(missing.getMessage(), containsString("certificates"));
        final var tooBig = assertThrows(ConfigurationNotValidException.class,
                () -> StateArchive.read(new ByteArrayInputStream(new byte[StateArchive.MAX_ARCHIVE_BYTES + 1])));
        assertThat(tooBig.getMessage(), containsString("64 MB"));
    }

    @Test
    public void testDefaultsAndUnknownFields() throws Exception {
        final var archive = read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"carapaceVersion\":\"2.1.4\",\"futureField\":{\"x\":1},"
                + "\"config\":{\"a\":\"b\"},\"keyPairs\":{},\"acmeAccountKeys\":{},"
                + "\"certificates\":{\"d.test\":{\"state\":\"WAITING\",\"provider\":\"zerossl\"}}}");
        assertEquals(Map.of("a", "b"), archive.config());
        assertNull(archive.carapaceCommit());
        final var snapshot = archive.toSnapshot("2.1.4", false);
        assertTrue(snapshot.keyPairs().isEmpty());
        assertTrue(snapshot.acmeAccountKeys().isEmpty());
        final var row = archive.certificates().get("d.test");
        assertEquals("WAITING", row.state());
        assertEquals(List.of(), row.subjectAltNames());
        assertEquals(0, row.attemptCount());
    }

    @Test
    public void testRoundTrip() throws Exception {
        final var archive = StateArchive.of(SNAPSHOT);
        assertEquals(CarapaceVersion.VERSION, archive.carapaceVersion());
        assertEquals(CarapaceVersion.COMMIT, archive.carapaceCommit());
        final var json = new String(archive.toJson(), StandardCharsets.UTF_8);
        // documented field order
        final var order = List.of("\"format\"", "\"formatVersion\"", "\"carapaceVersion\"", "\"carapaceCommit\"", "\"createdAt\"",
                "\"config\"", "\"keyPairs\"", "\"acmeAccountKeys\"", "\"certificates\"");
        for (int i = 1; i < order.size(); i++) {
            assertTrue(json, json.indexOf(order.get(i - 1)) < json.indexOf(order.get(i)));
        }
        final var read = read(json);
        assertEquals(archive, read);
        assertEquals(SNAPSHOT, read.toSnapshot(CarapaceVersion.VERSION, false));
    }
}
