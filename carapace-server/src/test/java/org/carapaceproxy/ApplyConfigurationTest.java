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
package org.carapaceproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Properties;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUserRealm;
import org.carapaceproxy.utils.TestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author enrico.olivelli
 */
public class ApplyConfigurationTest {

    @ClassRule
    public static WireMockRule wireMockRule = new WireMockRule(0);
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @BeforeClass
    public static void setupWireMock() {
        stubFor(get(urlEqualTo("/index.html?redir"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Pragma", "no-cache")
                        .withHeader("Connection", "close")
                        .withBody("it <b>works</b> !!")));

    }

    private static CloseableHttpClient createHttpClientWithDisabledSSLValidation() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        return HttpClients.custom()
                .setSSLContext(SSLContextBuilder.create()
                        .loadTrustMaterial((chain, authType) -> true) // Trust all certificates
                        .build())
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE) // Disable hostname verification
                .build();
    }

    @Test
    public void testChangeListenersConfig() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(propsWithMapper(Map.of(
                    "aws.accesskey", "accesskey",
                    "aws.secretkey", "secretkey"
            ))));

            // start without listeners
            server.start();

            // start two listeners
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426"
            )));

            testIt(1423, true);
            testIt(1426, true);

            // restart listener 1
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426"
            )));

            testIt(1425, true);
            testIt(1426, true);

            // stop listener 2
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425"
            )));

            testIt(1425, true);
            testIt(1426, false);

            // restart listener 2
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426"
            )));

            testIt(1425, true);
            testIt(1426, true);

            // no more listeners
            reloadConfiguration(server, propsWithMapper(Map.of()));

            testIt(1425, false);
            testIt(1426, false);

            // listener with correct tls version
            String defaultCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
            reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.1.ssl", "true",
                    "listener.1.sslprotocols", "TLSv1.2",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426",
                    "listener.2.ssl", "true",
                    "listener.2.sslprotocols", "TLSv1.2,TLSv1.3"
            )));

            // Test HTTPS for listener 1
            testIt(1423, true, true); // Expecting valid HTTPS connection
            // Test HTTPS for listener 2
            testIt(1426, true, true); // Expecting valid HTTPS connection

            // listener with default tls version
            reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.1.ssl", "true"
            )));
            // Test HTTPS for listener 1
            testIt(1423, true, true); // Expecting valid HTTPS connection

            // listener with wrong tls version
            final IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                    reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.1.ssl", "true",
                    "listener.1.sslprotocols", "TLSUNKNOWN"
            ))));
            Throwable cause = e.getCause();
            assertThat(cause, instanceOf(ConfigurationNotValidException.class));
            assertThat(cause.getMessage(), containsString("Unsupported SSL Protocols"));
        }
    }

    @Test
    public void testReloadMapper() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(new Properties()));
            server.start();

            assertThat(server.getMapper(), instanceOf(StandardEndpointMapper.class));
            assertThat(server.getMapper().getBackends(), is(anEmptyMap()));

            // add backend
            reloadConfiguration(server, props(Map.of(
                    "backend.1.id", "foo",
                    "backend.1.host", "my-host1",
                    "backend.1.port", "4213",
                    "backend.1.enabled", "true"
            )));
            assertThat(server.getMapper(), instanceOf(StandardEndpointMapper.class));
            assertThat(server.getMapper().getBackends(), allOf(
                    is(aMapWithSize(1)),
                    hasKey("foo")
            ));

            // add second backend
            reloadConfiguration(server, props(Map.of(
                    "backend.1.id", "foo",
                    "backend.1.host", "my-host1",
                    "backend.1.port", "4213",
                    "backend.1.enabled", "true",
                    "backend.2.id", "bar",
                    "backend.2.host", "my-host2",
                    "backend.2.port", "4213",
                    "backend.2.enabled", "true"
            )));

            assertThat(server.getMapper(), instanceOf(StandardEndpointMapper.class));
            assertThat(server.getMapper().getBackends(), allOf(
                    is(aMapWithSize(2)),
                    hasKey("foo"),
                    hasKey("bar")
            ));

            // remove first backend
            reloadConfiguration(server, props(Map.of(
                    "backend.2.id", "bar",
                    "backend.2.host", "my-host2",
                    "backend.2.port", "4213",
                    "backend.2.enabled", "true"
            )));

            assertThat(server.getMapper(), instanceOf(StandardEndpointMapper.class));
            assertThat(server.getMapper().getBackends(), allOf(
                    is(aMapWithSize(1)),
                    hasKey("bar")
            ));
        }
    }

    private void reloadConfiguration(final HttpProxyServer server, final Properties configuration) throws ConfigurationChangeInProgressException, InterruptedException {
        PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
        server.applyDynamicConfigurationFromAPI(config);
    }

    private Properties props(final Map<String, String> props) {
        final var configuration = new Properties(props.size() + 1);
        configuration.putAll(props);
        return configuration;
    }

    @Test
    public void testUserRealm() throws Exception {

        // Default UserRealm
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(new Properties()));
            server.start();

            UserRealm realm = server.getRealm();
            assertThat(realm, is(instanceOf(SimpleUserRealm.class)));

            // default user with auth always valid
            SimpleUserRealm userRealm = (SimpleUserRealm) server.getRealm();
            assertThat(userRealm.listUsers(), hasSize(1));

            assertNotNull(userRealm.login("test_0", "anypass0"));
            assertNotNull(userRealm.login("test_1", "anypass1"));
            assertNotNull(userRealm.login("test_2", "anypass2"));
        }

        // TestUserRealm
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(props(Map.of(
                    "userrealm.class", "org.carapaceproxy.utils.TestUserRealm",
                    "user.test1", "pass1",
                    "user.test2", "pass2"
            ))));
            server.start();

            UserRealm realm = server.getRealm();
            assertThat(realm, is(instanceOf(TestUserRealm.class)));

            TestUserRealm userRealm = (TestUserRealm) server.getRealm();
            assertThat(userRealm.listUsers(), hasSize(2));
            assertNotNull(userRealm.login("test1", "pass1"));
            assertNotNull(userRealm.login("test2", "pass2"));
            assertNull(userRealm.login("test1", "pass3")); // wrong pass

            // Add new user
            reloadConfiguration(server, props(Map.of(
                    "userrealm.class", "org.carapaceproxy.utils.TestUserRealm",
                    "user.test1", "pass1",
                    "user.test2", "pass2",
                    "user.test3", "pass3"
            )));

            userRealm = (TestUserRealm) server.getRealm(); // realm re-created at each configuration reload
            assertThat(userRealm.listUsers(), hasSize(3));
            assertNotNull(userRealm.login("test3", "pass3"));
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testChangeFiltersConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(props("filter.1.type", "add-x-forwarded-for")));
            server.start();
            assertThat(server.getFilters(), hasSize(1));
            assertThat(server.getFilters().get(0), instanceOf(XForwardedForRequestFilter.class));

            // add a filter
            reloadConfiguration(server, props(Map.of(
                    "filter.1.type", "add-x-forwarded-for",
                    "filter.2.type", "match-user-regexp"
            )));

            assertThat(server.getFilters(), hasSize(2));
            assertThat(server.getFilters().get(0), is(instanceOf(XForwardedForRequestFilter.class)));
            assertThat(server.getFilters().get(1), is(instanceOf(RegexpMapUserIdFilter.class)));

            // remove a filter
            reloadConfiguration(server, props("filter.2.type", "match-user-regexp"));
            assertThat(server.getFilters(), hasSize(1));
            assertThat(server.getFilters().get(0), is(instanceOf(RegexpMapUserIdFilter.class)));
        }
    }

    private Properties props(final String key, final String value) {
        return props(Map.of(key, value));
    }

    @Test
    public void testChangeBackendHealthManagerConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            server.configureAtBoot(new PropertiesConfigurationStore(props("healthmanager.connecttimeout", "9479")));
            server.start();
            assertEquals(9479, server.getBackendHealthManager().getConnectTimeout());

            // change configuration
            reloadConfiguration(server, props("healthmanager.connecttimeout", "9233"));
            assertEquals(9233, server.getBackendHealthManager().getConnectTimeout());
        }
    }

    private void testIt(int port, boolean ok) throws Exception {
        testIt(port, false, ok);
    }

    private void testIt(int port, final boolean https, boolean ok) throws Exception {
        try (CloseableHttpClient client = createHttpClientWithDisabledSSLValidation()) {
            final String protocol = https ? "https" : "http";
            String url = protocol + "://localhost:" + port + "/index.html?redir";

            HttpGet request = new HttpGet(new URI(url));
            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);

                System.out.println("RES FOR: " + url + " -> " + responseBody);

                // Check that the response body matches what we expect
                assertEquals("it <b>works</b> !!", responseBody);

                if (!ok && statusCode == 200) {
                    fail("Expecting an error for port " + port);
                }
            }
        } catch (IOException err) {
            if (ok) {
                fail("unexpected error " + err + " for port " + port);
            }
        }
    }

    private Properties propsWithMapper(final Map<String, String> props) {
        final var configuration = new Properties(props.size());
        configuration.put("mapper.class", StaticEndpointMapper.class.getName());
        configuration.putAll(props);
        return configuration;
    }

    private Properties propsWithMapperAndCertificate(final String defaultCertificate, final Map<String, String> props) {
        final var configuration = new Properties(props.size());
        configuration.put("mapper.class", StaticEndpointMapper.class.getName());
        configuration.put("certificate.1.hostname", "*");
        configuration.put("certificate.1.file", defaultCertificate);
        configuration.put("certificate.1.password", "changeit");
        configuration.putAll(props);
        return configuration;
    }

    /**
     * Static mapper, so that it can be references by configuration
     */
    public static final class StaticEndpointMapper extends TestEndpointMapper {
        public StaticEndpointMapper() {
            super("localhost", wireMockRule.port());
        }
    }
}
