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
import static org.carapaceproxy.utils.ApacheHttpUtils.createHttpClientWithDisabledSSLValidation;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.RestoreInProgressException;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUserRealm;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

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

            assertServes(1423, false);
            assertServes(1426, false);

            // restart listener 1
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426"
            )));

            assertServes(1425, false);
            assertServes(1426, false);

            // stop listener 2
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425"
            )));

            assertServes(1425, false);
            assertRefused(1426);

            // restart listener 2
            reloadConfiguration(server, propsWithMapper(Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1425",
                    "listener.2.host", "localhost",
                    "listener.2.port", "1426"
            )));

            assertServes(1425, false);
            assertServes(1426, false);

            // no more listeners
            reloadConfiguration(server, propsWithMapper(Map.of()));

            assertRefused(1425);
            assertRefused(1426);

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
            assertServes(1423, true);
            assertServes(1426, true);

            // listener with default tls version
            reloadConfiguration(server, propsWithMapperAndCertificate(defaultCertificate, Map.of(
                    "listener.1.host", "localhost",
                    "listener.1.port", "1423",
                    "listener.1.ssl", "true"
            )));

            // Expecting valid HTTPS connection
            assertServes(1423, true);

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

        // no realm configured: the admin API is disabled here, so the server boots without one
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(new Properties()));
            server.start();

            assertThat(server.getRealm()).isNull();
        }

        // no realm configured while the admin API is enabled: the server refuses to boot
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> server.configureAtBoot(
                    new PropertiesConfigurationStore(props("http.admin.enabled", "true"))))
                    .withMessageContaining("userrealm.class is required when the admin API is enabled");
        }

        // SimpleUserRealm: the development stub, which has to be asked for explicitly
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(
                    props("userrealm.class", SimpleUserRealm.class.getName())));
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
            assertThat(server.getFilters()).hasExactlyElementsOfTypes(XForwardedForRequestFilter.class);

            // add a filter
            reloadConfiguration(server, props(Map.of(
                    "filter.1.type", "add-x-forwarded-for",
                    "filter.2.type", "match-user-regexp"
            )));

            assertThat(server.getFilters()).hasExactlyElementsOfTypes(XForwardedForRequestFilter.class, RegexpMapUserIdFilter.class);

            // remove a filter
            reloadConfiguration(server, props("filter.2.type", "match-user-regexp"));
            assertThat(server.getFilters()).hasExactlyElementsOfTypes(RegexpMapUserIdFilter.class);
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

    @Test
    void writesRefusedWhileRestoreRuns() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            Properties configuration = new Properties();
            configuration.put("mapper.class", StaticEndpointMapper.class.getName());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            server.start();
            final GroupMembershipHandler handler = server.getGroupMembershipHandler();

            try (GroupMembershipHandler.RestoreSignal signal = handler.raiseRestoreSignal()) {
                assertThatExceptionOfType(RestoreInProgressException.class).isThrownBy(() -> server.updateDynamicCertificateForDomain(new CertificateData("a.example", null, DynamicCertificateState.WAITING)));
                assertThat(server.getDynamicConfigurationStore().loadCertificateForDomain("a.example")).isNull();
            }

            server.updateDynamicCertificateForDomain(new CertificateData("a.example", null, DynamicCertificateState.WAITING));
            try (GroupMembershipHandler.RestoreSignal signal = handler.raiseRestoreSignal()) {
                assertThatExceptionOfType(RestoreInProgressException.class).isThrownBy(() -> server.getDynamicCertificatesManager().setStateOfCertificate("a.example", DynamicCertificateState.AVAILABLE));
            }
            assertThat(server.getDynamicConfigurationStore().loadCertificateForDomain("a.example").getState()).isEqualTo(DynamicCertificateState.WAITING);
        }
    }

    @Test
    void certificateRollbackSkippedWhenRestoreStarts() throws Exception {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<GroupMembershipHandler.RestoreSignal> signal = new AtomicReference<>();
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            Properties configuration = new Properties();
            configuration.put("mapper.class", StaticEndpointMapper.class.getName());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration) {
                @Override
                public void saveCertificate(CertificateData cert) {
                    super.saveCertificate(cert);
                    // a restore starts right after the uploaded row is written
                    if (armed.getAndSet(false)) {
                        try {
                            signal.set(server.getGroupMembershipHandler().raiseRestoreSignal());
                        } catch (ConfigurationChangeInProgressException err) {
                            throw new IllegalStateException(err);
                        }
                    }
                }
            });
            server.start();
            server.updateDynamicCertificateForDomain(new CertificateData("a.example", null, DynamicCertificateState.WAITING));

            try {
                // an apply failing for another reason while the signal is up: the previous row would overwrite the restored one
                final CertificateData unknownProvider = new CertificateData("a.example", null, DynamicCertificateState.REQUEST_FAILED);
                unknownProvider.setProvider("ghost");
                armed.set(true);
                assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> server.updateDynamicCertificateForDomain(unknownProvider))
                        .withMessageContaining("no such ACME provider");
                assertThat(server.getDynamicConfigurationStore().loadCertificateForDomain("a.example").getState()).isEqualTo(DynamicCertificateState.REQUEST_FAILED);
            } finally {
                Optional.ofNullable(signal.getAndSet(null)).ifPresent(GroupMembershipHandler.RestoreSignal::close);
            }

            try {
                // the apply is refused at its commit
                armed.set(true);
                assertThatExceptionOfType(RestoreInProgressException.class).isThrownBy(() -> server.updateDynamicCertificateForDomain(new CertificateData("a.example", null, DynamicCertificateState.AVAILABLE)));
                assertThat(server.getDynamicConfigurationStore().loadCertificateForDomain("a.example").getState()).isEqualTo(DynamicCertificateState.AVAILABLE);
            } finally {
                Optional.ofNullable(signal.getAndSet(null)).ifPresent(GroupMembershipHandler.RestoreSignal::close);
            }
        }
    }

    /**
     * Asserts that the listener on the given port serves the static index page.
     *
     * @param port  the listener port
     * @param https whether to connect over TLS
     * @throws Exception if the request cannot be sent or its body read
     */
    private void assertServes(int port, boolean https) throws Exception {
        final String url = (https ? "https" : "http") + "://localhost:" + port + "/index.html?redir";
        try (CloseableHttpClient client = createHttpClientWithDisabledSSLValidation();
             CloseableHttpResponse response = client.execute(new HttpGet(url))) {
            assertThat(response.getStatusLine().getStatusCode()).as(url).isEqualTo(HttpStatus.SC_OK);
            assertThat(new String(response.getEntity().getContent().readAllBytes(), UTF_8)).as(url).isEqualTo("it <b>works</b> !!");
        }
    }

    /**
     * Asserts that no listener accepts HTTP requests on the given port.
     *
     * @param port the port of a listener that has been removed or never configured
     * @throws Exception if the HTTP client cannot be created
     */
    private void assertRefused(int port) throws Exception {
        final String url = "http://localhost:" + port + "/index.html?redir";
        try (CloseableHttpClient client = createHttpClientWithDisabledSSLValidation()) {
            assertThatExceptionOfType(IOException.class).as(url).isThrownBy(() -> client.execute(new HttpGet(url)).close());
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
