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
package org.carapaceproxy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.config.AcmeProviderConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the {@code acme.<n>.*} provider family and the {@code certificate.<n>.provider} property.
 */
class RuntimeServerConfigurationTest {

    private static RuntimeServerConfiguration configure(String... keyValues) throws ConfigurationNotValidException {
        final var props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        final var config = new RuntimeServerConfiguration();
        config.configure(new PropertiesConfigurationStore(props));
        return config;
    }

    @Test
    void emptyConfigurationYieldsValidDefaults() throws Exception {
        final var conf = configure();

        assertThat(conf.getMaxHeaderSize()).isEqualTo(8192);
        assertThat(conf.getIdleTimeout()).isEqualTo(60000);
        assertThat(conf.getMaxLifeTime()).isEqualTo(100000);
        assertThat(conf.getDisposeTimeout()).isEqualTo(300000);
        assertThat(conf.getHealthConnectTimeout()).isEqualTo(5000);
        assertThat(conf.getWarmupPeriod()).isEqualTo(30000L);
        assertThat(conf.getAccessLogTimestampFormat()).isEqualTo("yyyy-MM-dd HH:mm:ss.SSS");
        assertThat(conf.getListeners()).isEmpty();
        assertThat(conf.getCertificates()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "connectionsmanager.idletimeout, 0",
            "connectionsmanager.maxlifetime, 0",
            "carapace.maxheadersize, 0",
            "healthmanager.connecttimeout, -1"
    })
    void rejectsNonPositiveNumericConfig(String key, String value) {
        assertThatThrownBy(() -> configure(key, value)).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("Invalid value '" + value + "' for " + key);
    }

    @Test
    void rejectsNonNumericIntValue() {
        assertThatThrownBy(() -> configure("connectionsmanager.disposetimeout", "abc")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("Invalid integer value 'abc' for parameter 'connectionsmanager.disposetimeout'");
    }

    @Test
    void rejectsInvalidAccessLogTimestampFormat() {
        assertThatThrownBy(() -> configure("accesslog.format.timestamp", "'unterminated")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("accesslog.format.timestamp")
                .hasMessageContaining("Unterminated quote");
    }

    @Test
    void rejectsNonWritableLocalCertificatesStorePath() {
        assertThatThrownBy(() -> configure(
                "dynamiccertificatesmanager.localcertificates.store.path", "/nonexistent-carapace-test-dir/certs")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("Cannot write local certificates to path");
    }

    @Test
    void rejectsInvalidCertificateMode() {
        assertThatThrownBy(() -> configure(
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "badmode")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("Invalid value of 'badmode' for certificate.0.mode");
    }

    @Test
    void rejectsConnectionPoolWithEmptyDomain() {
        assertThatThrownBy(() -> configure(
                "connectionpool.0.id", "p0",
                "connectionpool.0.enabled", "true",
                "connectionpool.0.domain", "")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("domain cannot be empty");
    }

    @Test
    void rejectsUnknownFilterType() {
        assertThatThrownBy(() -> configure("filter.0.type", "badtype")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("bad filter type 'badtype'");
    }

    @Test
    void rejectsSslListenerWithoutDefaultCertificate() {
        assertThatThrownBy(() -> configure(
                "listener.0.port", "8080",
                "listener.0.ssl", "true")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("ssl=true")
                .hasMessageContaining("not configured");
    }

    @Test
    void rejectsInvalidListenerProtocol() {
        // Regression: an unknown protocol used to escape as a raw IllegalArgumentException.
        assertThatThrownBy(() -> configure(
                "listener.0.port", "8080",
                "listener.0.protocol", "BADPROTO")).isInstanceOf(ConfigurationNotValidException.class)
                .hasMessageContaining("Invalid value for listener.0.protocol, supported: HTTP11, H2, H2C");
    }

    @Test
    void acceptsValidListenerProtocol() {
        assertThatCode(() -> configure(
                "listener.0.port", "8080",
                "listener.0.protocol", "H2C")).doesNotThrowAnyException();
    }

    @Test
    void configureAcmeProviders() throws Exception {
        final var config = configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.digicert.com/v2/acme/directory",
                "acme.1.kid", "my-kid",
                "acme.1.hmac", "my-base64-hmac",
                "acme.2.name", "pebble",
                "acme.2.url", "https://localhost:14000/dir"
        );
        assertThat(config.getAcmeProviders()).hasSize(2);

        final var digicert = config.getAcmeProviders().get("digicert");
        assertThat(digicert).isEqualTo(new AcmeProviderConfiguration(
                "digicert", "https://acme.digicert.com/v2/acme/directory", "my-kid", "my-base64-hmac"));
        assertThat(digicert.hasExternalAccountBinding()).isTrue();
        assertThat(digicert.toString()).doesNotContain(digicert.hmac()); // the hmac is a secret

        final var pebble = config.getAcmeProviders().get("pebble");
        assertThat(pebble).isEqualTo(new AcmeProviderConfiguration("pebble", "https://localhost:14000/dir", "", ""));
        assertThat(pebble.hasExternalAccountBinding()).isFalse();
    }

    @Test
    void acmeProviderWithoutNameIsSkipped() throws Exception {
        final var config = configure("acme.1.url", "https://acme.example.com/directory");
        assertThat(config.getAcmeProviders()).isEmpty();
    }

    @Test
    void acmeProviderReservedName() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", DEFAULT_PROVIDER_NAME,
                "acme.1.url", "https://acme.example.com/directory"
        )).actual();
        assertThat(e.getMessage()).contains("built-in");
    }

    @Test
    void acmeProviderInvalidName() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "Not A Valid Name!",
                "acme.1.url", "https://acme.example.com/directory"
        )).actual();
        assertThat(e.getMessage()).contains("acme.1.name");
    }

    @Test
    void acmeProviderDuplicateName() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.2.name", "digicert",
                "acme.2.url", "https://acme.example.org/directory"
        )).actual();
        assertThat(e.getMessage()).contains("duplicate");
    }

    @Test
    void acmeProviderMissingUrl() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert"
        )).actual();
        assertThat(e.getMessage()).contains("url");
    }

    @Test
    void acmeProviderAcmeUriAccepted() throws Exception {
        final var config = configure(
                "acme.1.name", "pebble",
                "acme.1.url", "acme://pebble"
        );
        assertThat(config.getAcmeProviders().get("pebble").url()).isEqualTo("acme://pebble");
    }

    @Test
    void acmeProviderUnknownAcmeUri() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "pebble",
                "acme.1.url", "acme://pebbel" // typo: no such acme4j provider
        )).actual();
        assertThat(e.getMessage()).contains("acme.1.url");
    }

    @Test
    void acmeProviderUppercaseSchemeRejected() {
        // acme4j resolves providers by exact scheme match, so accepting HTTPS:// at parse time
        // would only defer the failure to the first login attempt
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "HTTPS://acme.example.com/directory"
        )).actual();
        assertThat(e.getMessage()).contains("scheme");
    }

    @Test
    void acmeProviderMalformedUrl() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "not a valid url"
        )).actual();
        assertThat(e.getMessage()).contains("acme.1.url");
    }

    @Test
    void acmeProviderUnsupportedUrlScheme() {
        for (final var url : new String[]{"ftp://acme.example.com/directory", "http://acme.example.com/directory"}) {
            final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                    "acme.1.name", "digicert",
                    "acme.1.url", url
            )).actual();
            assertThat(e.getMessage()).contains("scheme");
        }
    }

    @Test
    void acmeProviderInvalidHmac() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.1.kid", "my-kid",
                "acme.1.hmac", "not+valid/base64url!"
        )).actual();
        assertThat(e.getMessage()).contains("base64url");
    }

    @Test
    void acmeProviderKidWithoutHmac() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.1.kid", "my-kid"
        )).actual();
        assertThat(e.getMessage()).contains("hmac");
    }

    @Test
    void certificateDefaultProvider() throws Exception {
        final var config = configure(
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme"
        );
        assertThat(config.getCertificates().get("example.com").getProvider()).isEqualTo(DEFAULT_PROVIDER_NAME);
    }

    @Test
    void certificateWithCustomProvider() throws Exception {
        final var config = configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.digicert.com/v2/acme/directory",
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme",
                "certificate.0.provider", "digicert"
        );
        assertThat(config.getCertificates().get("example.com").getProvider()).isEqualTo("digicert");
    }

    @Test
    void certificateWithUnknownProvider() {
        final var e = assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> configure(
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme",
                "certificate.0.provider", "unknown"
        )).actual();
        assertThat(e.getMessage()).contains("certificate.0.provider");
    }
}
