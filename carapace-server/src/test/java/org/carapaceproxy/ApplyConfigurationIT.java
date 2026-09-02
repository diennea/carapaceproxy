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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.carapaceproxy.utils.ApacheHttpUtils.createHttpClientWithDisabledSSLValidation;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author enrico.olivelli
 */
public class ApplyConfigurationIT {

    @RegisterExtension
    public static WireMockExtension wireMockRule = WireMockExtension.newInstance().options(WireMockConfiguration.options().port(0)).configureStaticDsl(true).build();
    @TempDir
    public File tmpDir;

    @BeforeEach
    void setupWireMock() {
        wireMockRule.stubFor(get(urlEqualTo("/index.html?redir"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Pragma", "no-cache")
                        .withHeader("Connection", "close")
                        .withBody("it <b>works</b> !!")));

    }

    @Test
    void changeListenersConfig() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"));) {
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
            String defaultCertificate = TestUtils.deployResource("ia.p12", tmpDir);
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

            // Expecting valid HTTPS connection
            testIt(1423, true, true);
            testIt(1426, true, true);

            // listener with default tls version
            reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.1.ssl", "true"
            )));

            // Expecting valid HTTPS connection
            testIt(1423, true, true);

            // listener with wrong tls version
            final IllegalStateException e = assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                    reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                            "listener.1.host", "localhost",
                            "listener.1.port", "1423",
                            "listener.1.ssl", "true",
                            "listener.1.sslprotocols", "TLSUNKNOWN"
                    )))).actual();
            Throwable cause = e.getCause();
            assertThat(cause).isInstanceOf(ConfigurationNotValidException.class);
            assertThat(cause.getMessage()).contains("Unsupported SSL Protocols");
        }
    }

    @Test
    void reloadMapper() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"));) {
            server.configureAtBoot(new PropertiesConfigurationStore(new Properties()));
            server.start();

            assertThat(server.getMapper()).isInstanceOf(StandardEndpointMapper.class);
            assertThat(server.getMapper().getBackends()).isEmpty();

            // add backend
            reloadConfiguration(server, props(Map.of(
                    "backend.1.id", "foo",
                    "backend.1.host", "my-host1",
                    "backend.1.port", "4213",
                    "backend.1.enabled", "true"
            )));
            assertThat(server.getMapper()).isInstanceOf(StandardEndpointMapper.class);
            assertThat(server.getMapper().getBackends())
                    .hasSize(1)
                    .containsKey("foo");

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

            assertThat(server.getMapper()).isInstanceOf(StandardEndpointMapper.class);
            assertThat(server.getMapper().getBackends())
                    .hasSize(2)
                    .containsKey("foo")
                    .containsKey("bar");

            // remove first backend
            reloadConfiguration(server, props(Map.of(
                    "backend.2.id", "bar",
                    "backend.2.host", "my-host2",
                    "backend.2.port", "4213",
                    "backend.2.enabled", "true"
            )));

            assertThat(server.getMapper()).isInstanceOf(StandardEndpointMapper.class);
            assertThat(server.getMapper().getBackends())
                    .hasSize(1)
                    .containsKey("bar");
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
    void userRealm() throws Exception {

        // Default UserRealm
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(new Properties()));
            server.start();

            UserRealm realm = server.getRealm();
            assertThat(realm).isInstanceOf(SimpleUserRealm.class);

            // default user with auth always valid
            SimpleUserRealm userRealm = (SimpleUserRealm) server.getRealm();
            assertThat(userRealm.listUsers()).hasSize(1);

            assertThat(userRealm.login("test_0", "anypass0")).isNotNull();
            assertThat(userRealm.login("test_1", "anypass1")).isNotNull();
            assertThat(userRealm.login("test_2", "anypass2")).isNotNull();
        }

        // TestUserRealm
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(props(Map.of(
                    "userrealm.class", "org.carapaceproxy.utils.TestUserRealm",
                    "user.test1", "pass1",
                    "user.test2", "pass2"
            ))));
            server.start();

            UserRealm realm = server.getRealm();
            assertThat(realm).isInstanceOf(TestUserRealm.class);

            TestUserRealm userRealm = (TestUserRealm) server.getRealm();
            assertThat(userRealm.listUsers()).hasSize(2);
            assertThat(userRealm.login("test1", "pass1")).isNotNull();
            assertThat(userRealm.login("test2", "pass2")).isNotNull();
            assertThat(userRealm.login("test1", "pass3")).isNull(); // wrong pass

            // Add new user
            reloadConfiguration(server, props(Map.of(
                    "userrealm.class", "org.carapaceproxy.utils.TestUserRealm",
                    "user.test1", "pass1",
                    "user.test2", "pass2",
                    "user.test3", "pass3"
            )));

            userRealm = (TestUserRealm) server.getRealm(); // realm re-created at each configuration reload
            assertThat(userRealm.listUsers()).hasSize(3);
            assertThat(userRealm.login("test3", "pass3")).isNotNull();
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void changeFiltersConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"));) {
            server.configureAtBoot(new PropertiesConfigurationStore(props("filter.1.type", "add-x-forwarded-for")));
            server.start();
            assertThat(server.getFilters()).hasSize(1);
            assertThat(server.getFilters().get(0)).isInstanceOf(XForwardedForRequestFilter.class);

            // add a filter
            reloadConfiguration(server, props(Map.of(
                    "filter.1.type", "add-x-forwarded-for",
                    "filter.2.type", "match-user-regexp"
            )));

            assertThat(server.getFilters()).hasSize(2);
            assertThat(server.getFilters().get(0)).isInstanceOf(XForwardedForRequestFilter.class);
            assertThat(server.getFilters().get(1)).isInstanceOf(RegexpMapUserIdFilter.class);

            // remove a filter
            reloadConfiguration(server, props("filter.2.type", "match-user-regexp"));
            assertThat(server.getFilters()).hasSize(1);
            assertThat(server.getFilters().get(0)).isInstanceOf(RegexpMapUserIdFilter.class);
        }
    }

    private Properties props(final String key, final String value) {
        return props(Map.of(key, value));
    }

    @Test
    void changeBackendHealthManagerConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"));) {
            server.configureAtBoot(new PropertiesConfigurationStore(props("healthmanager.connecttimeout", "9479")));
            server.start();
            assertThat(server.getBackendHealthManager().getConnectTimeout()).isEqualTo(9479);

            // change configuration
            reloadConfiguration(server, props("healthmanager.connecttimeout", "9233"));
            assertThat(server.getBackendHealthManager().getConnectTimeout()).isEqualTo(9233);
        }
    }

    private void testIt(int port, boolean ok) throws Exception {
        testIt(port, false, ok);
    }

    private void testIt(int port, final boolean https, boolean ok) throws Exception {
        try (final CloseableHttpClient client = createHttpClientWithDisabledSSLValidation()) {
            final String protocol = https ? "https" : "http";
            final String url = protocol + "://localhost:" + port + "/index.html?redir";

            final HttpGet request = new HttpGet(new URI(url));
            try (final CloseableHttpResponse response = client.execute(request)) {
                final int statusCode = response.getStatusLine().getStatusCode();
                final String responseBody = new String(response.getEntity().getContent().readAllBytes(), UTF_8);

                System.out.println("RES FOR: " + url + " -> " + responseBody);
                assertThat(responseBody).isEqualTo("it <b>works</b> !!");

                if (!ok && statusCode == HttpStatus.SC_OK) {
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
        configuration.put("certificate.1.mode", "static");
        configuration.putAll(props);
        return configuration;
    }

    /**
     * Static mapper, so that it can be references by configuration
     */
    public static final class StaticEndpointMapper extends TestEndpointMapper {

        public StaticEndpointMapper(final HttpProxyServer ignoredServer) {
            this(); // required for reflective construction
        }

        public StaticEndpointMapper() {
            super("localhost", wireMockRule.getPort());
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
