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

import static org.carapaceproxy.server.config.AcmeProviderConfiguration.DEFAULT_PROVIDER_NAME;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.junit.Test;

/**
 * Tests for the {@code acme.<n>.*} provider family and the {@code certificate.<n>.provider} property.
 */
public class RuntimeServerConfigurationTest {

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
    public void testConfigureAcmeProviders() throws Exception {
        final var config = configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.digicert.com/v2/acme/directory",
                "acme.1.kid", "my-kid",
                "acme.1.hmac", "my-base64-hmac",
                "acme.2.name", "pebble",
                "acme.2.url", "https://localhost:14000/dir"
        );
        assertEquals(2, config.getAcmeProviders().size());

        final var digicert = config.getAcmeProviders().get("digicert");
        assertEquals("https://acme.digicert.com/v2/acme/directory", digicert.url());
        assertEquals("my-kid", digicert.kid());
        assertEquals("my-base64-hmac", digicert.hmac());
        assertTrue(digicert.hasExternalAccountBinding());
        assertThat(digicert.toString(), not(containsString(digicert.hmac()))); // the hmac is a secret

        final var pebble = config.getAcmeProviders().get("pebble");
        assertEquals("https://localhost:14000/dir", pebble.url());
        assertFalse(pebble.hasExternalAccountBinding());
    }

    @Test
    public void testAcmeProviderWithoutNameIsSkipped() throws Exception {
        final var config = configure("acme.1.url", "https://acme.example.com/directory");
        assertTrue(config.getAcmeProviders().isEmpty());
    }

    @Test
    public void testAcmeProviderReservedName() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", DEFAULT_PROVIDER_NAME,
                "acme.1.url", "https://acme.example.com/directory"
        ));
        assertThat(e.getMessage(), containsString("built-in"));
    }

    @Test
    public void testAcmeProviderInvalidName() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "Not A Valid Name!",
                "acme.1.url", "https://acme.example.com/directory"
        ));
        assertThat(e.getMessage(), containsString("acme.1.name"));
    }

    @Test
    public void testAcmeProviderDuplicateName() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.2.name", "digicert",
                "acme.2.url", "https://acme.example.org/directory"
        ));
        assertThat(e.getMessage(), containsString("duplicate"));
    }

    @Test
    public void testAcmeProviderMissingUrl() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert"
        ));
        assertThat(e.getMessage(), containsString("url"));
    }

    @Test
    public void testAcmeProviderAcmeUriAccepted() throws Exception {
        final var config = configure(
                "acme.1.name", "pebble",
                "acme.1.url", "acme://pebble"
        );
        assertEquals("acme://pebble", config.getAcmeProviders().get("pebble").url());
    }

    @Test
    public void testAcmeProviderUnknownAcmeUri() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "pebble",
                "acme.1.url", "acme://pebbel" // typo: no such acme4j provider
        ));
        assertThat(e.getMessage(), containsString("acme.1.url"));
    }

    @Test
    public void testAcmeProviderUppercaseSchemeRejected() {
        // acme4j resolves providers by exact scheme match, so accepting HTTPS:// at parse time
        // would only defer the failure to the first login attempt
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "HTTPS://acme.example.com/directory"
        ));
        assertThat(e.getMessage(), containsString("scheme"));
    }

    @Test
    public void testAcmeProviderMalformedUrl() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "not a valid url"
        ));
        assertThat(e.getMessage(), containsString("acme.1.url"));
    }

    @Test
    public void testAcmeProviderUnsupportedUrlScheme() {
        for (final var url : new String[]{"ftp://acme.example.com/directory", "http://acme.example.com/directory"}) {
            final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                    "acme.1.name", "digicert",
                    "acme.1.url", url
            ));
            assertThat(e.getMessage(), containsString("scheme"));
        }
    }

    @Test
    public void testAcmeProviderInvalidHmac() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.1.kid", "my-kid",
                "acme.1.hmac", "not+valid/base64url!"
        ));
        assertThat(e.getMessage(), containsString("base64url"));
    }

    @Test
    public void testAcmeProviderKidWithoutHmac() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.example.com/directory",
                "acme.1.kid", "my-kid"
        ));
        assertThat(e.getMessage(), containsString("hmac"));
    }

    @Test
    public void testCertificateDefaultProvider() throws Exception {
        final var config = configure(
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme"
        );
        assertEquals(DEFAULT_PROVIDER_NAME, config.getCertificates().get("example.com").getProvider());
    }

    @Test
    public void testCertificateWithCustomProvider() throws Exception {
        final var config = configure(
                "acme.1.name", "digicert",
                "acme.1.url", "https://acme.digicert.com/v2/acme/directory",
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme",
                "certificate.0.provider", "digicert"
        );
        assertEquals("digicert", config.getCertificates().get("example.com").getProvider());
    }

    @Test
    public void testCertificateWithUnknownProvider() {
        final var e = assertThrows(ConfigurationNotValidException.class, () -> configure(
                "certificate.0.hostname", "example.com",
                "certificate.0.mode", "acme",
                "certificate.0.provider", "unknown"
        ));
        assertThat(e.getMessage(), containsString("certificate.0.provider"));
    }
}
