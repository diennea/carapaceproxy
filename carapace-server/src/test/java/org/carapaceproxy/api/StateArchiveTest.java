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

import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;

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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Test for {@link StateArchive}: format checks, version rule and round trip.
 */
class StateArchiveTest {

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
        assertThat(archive(archiveVersion).toSnapshot(runtimeVersion, acceptNewer)).as(archiveVersion + " on " + runtimeVersion).isEqualTo(SNAPSHOT);
    }

    @Test
    void versionRule() throws Exception {
        assertAccepted("2.1.3", "2.1.4", false);
        assertAccepted("2.0.9", "2.1.0", false);
        assertAccepted("2.1.4", "2.1.4", false);
        assertAccepted("2.1.4-SNAPSHOT", "2.1.4", false);
        assertAccepted("2.1.4", "2.1.4-SNAPSHOT", false);
        assertAccepted("10.0.0", "9.9.9", true);
        assertAccepted("2.1.4", "dev", false);
        assertAccepted("dev", "2.1.4", false);
        assertAccepted("not a version", "dev", false);
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive(null).toSnapshot("2.1.4", false))
                .withMessage("The archive has no valid carapaceVersion: null");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive("not a version").toSnapshot("2.1.4", true))
                .withMessage("The archive has no valid carapaceVersion: not a version");
        // too long to be a version number: not read as 2.1.123456789
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive("2.1.1234567890").toSnapshot("2.1.4", false))
                .withMessage("The archive has no valid carapaceVersion: 2.1.1234567890");

        final var err = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive("2.3.0").toSnapshot("2.1.4", false)).actual();
        assertThat(err.getMessage()).contains("2.3.0");
        assertThat(err.getMessage()).contains("acceptNewerVersion");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive("2.1.5-SNAPSHOT").toSnapshot("2.1.4", false))
                .withMessageContaining("comes from Carapace 2.1.5-SNAPSHOT, newer than the running 2.1.4");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> archive("10.0.0").toSnapshot("9.9.9", false))
                .withMessageContaining("comes from Carapace 10.0.0, newer than the running 9.9.9");
    }

    @Test
    void unusedAcmeAccountKeysAccepted() throws Exception {
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
            assertThat(archive.toSnapshot("2.1.4", false).acmeAccountKeys()).isEqualTo(keys);
        } finally {
            logger.detachAppender(logAppender);
        }
        final var warnings = logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings.stream().filter(warning -> warning.contains("unused")).count()).isOne();
        assertThat(warnings.stream().noneMatch(warning -> warning.contains("other") || warning.contains(DEFAULT_PROVIDER_NAME))).isTrue();
    }

    @Test
    void formatChecks() {
        final var version2 = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"formatVersion\":2,\"config\":{\"a\":\"b\"}}")).actual();
        assertThat(version2.getMessage()).contains("formatVersion 2");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"config\":{\"a\":\"b\"}}"))
                .withMessageContaining("Unsupported archive formatVersion 0");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"other\",\"formatVersion\":1}"))
                .withMessage("Invalid archive: format is not \"carapace-state\"");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"formatVersion\":1}"))
                .withMessage("Invalid archive: format is not \"carapace-state\"");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("not json"))
                .withMessageStartingWith("Invalid archive: malformed JSON");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read(""))
                .withMessageStartingWith("Invalid archive: malformed JSON");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("null"))
                .withMessage("Invalid archive: format is not \"carapace-state\"");
        final var duplicate = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\",\"a\":\"c\"}}")).actual();
        assertThat(duplicate.getMessage()).contains("line 1, column");
        // the parser would quote the token: an unquoted key must not reach the message, nor the logs
        final var leak = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"keyPairs\":{\"d.test\":{\"privateKey\":MIIEvQIBADANBg}}}")).actual();
        assertThat(leak.getMessage()).doesNotContain("MIIEvQ");
        assertThat(leak.getMessage()).contains("line 1, column");
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\"}}SECRET"))
                .withMessageStartingWith("Invalid archive: malformed JSON");
        final var missing = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"config\":{\"a\":\"b\"},\"keyPairs\":{},\"acmeAccountKeys\":{}}")).actual();
        assertThat(missing.getMessage()).contains("certificates");
        final var tooBig = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> StateArchive.read(new ByteArrayInputStream(new byte[StateArchive.MAX_ARCHIVE_BYTES + 1]))).actual();
        assertThat(tooBig.getMessage()).contains("64 MB");
    }

    @Test
    void defaultsAndUnknownFields() throws Exception {
        final var archive = read("{\"format\":\"carapace-state\",\"formatVersion\":1,\"carapaceVersion\":\"2.1.4\",\"futureField\":{\"x\":1},"
                + "\"config\":{\"a\":\"b\"},\"keyPairs\":{},\"acmeAccountKeys\":{},"
                + "\"certificates\":{\"d.test\":{\"state\":\"WAITING\",\"provider\":\"zerossl\"}}}");
        assertThat(archive.config()).containsExactlyInAnyOrderEntriesOf(Map.of("a", "b"));
        assertThat(archive.carapaceCommit()).isNull();
        final var snapshot = archive.toSnapshot("2.1.4", false);
        assertThat(snapshot.keyPairs()).isEmpty();
        assertThat(snapshot.acmeAccountKeys()).isEmpty();
        final var row = archive.certificates().get("d.test");
        assertThat(row.state()).isEqualTo("WAITING");
        assertThat(row.subjectAltNames()).containsExactlyElementsOf(of());
        assertThat(row.attemptCount()).isZero();
    }

    @Test
    void roundTrip() throws Exception {
        final var archive = StateArchive.of(SNAPSHOT);
        assertThat(archive.carapaceVersion()).isEqualTo(CarapaceVersion.VERSION);
        assertThat(archive.carapaceCommit()).isEqualTo(CarapaceVersion.COMMIT);
        final var json = new String(archive.toJson(), StandardCharsets.UTF_8);
        // documented field order
        final var order = List.of("\"format\"", "\"formatVersion\"", "\"carapaceVersion\"", "\"carapaceCommit\"", "\"createdAt\"",
                "\"config\"", "\"keyPairs\"", "\"acmeAccountKeys\"", "\"certificates\"");
        for (int i = 1; i < order.size(); i++) {
            assertThat(json.indexOf(order.get(i - 1)) < json.indexOf(order.get(i))).as(json).isTrue();
        }
        final var read = read(json);
        assertThat(read).isEqualTo(archive);
        assertThat(read.toSnapshot(CarapaceVersion.VERSION, false)).isEqualTo(SNAPSHOT);
    }
}
