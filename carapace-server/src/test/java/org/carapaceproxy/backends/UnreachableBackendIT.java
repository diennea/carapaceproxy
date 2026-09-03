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
package org.carapaceproxy.backends;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.carapaceproxy.server.backends.BackendHealthStatus.Status.COLD;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class UnreachableBackendIT {

    // Manually managed (not @RegisterExtension): testWithUnreachableBackend stops the backend mid-test.
    private final WireMockServer wireMockRule = new WireMockServer(WireMockConfiguration.options().port(0));

    @TempDir
    public File tmpDir;

    @BeforeEach
    void startWireMock() {
        wireMockRule.start();
        configureFor("localhost", wireMockRule.port());
    }

    @AfterEach
    void stopWireMock() {
        if (wireMockRule.isRunning()) {
            wireMockRule.stop();
        }
    }

    @ParameterizedTest(name = "useCache={0}")
    @ValueSource(booleans = {true, false})
    void withUnreachableBackend(boolean useCache) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        int dummyport = wireMockRule.port();
        wireMockRule.stop();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache, false);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(resp.getStatusLine()).isEqualTo("HTTP/1.1 503 Service Unavailable\r\n");
                assertThat(resp.getBodyString()).isEqualTo("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """);
            }
            assertThat(server.getBackendHealthManager().getBackendStatus(key).getStatus()).isSameAs(BackendHealthStatus.Status.DOWN);
            TestUtils.waitForCondition(() -> ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get() == 0, 10);
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "EMPTY_RESPONSE, true", "EMPTY_RESPONSE, false",
        "RANDOM_DATA_THEN_CLOSE, true", "RANDOM_DATA_THEN_CLOSE, false"
    })
    void faultyResponseKeepsBackendCold(Fault fault, boolean useCache) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(fault)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache, false);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            Properties properties = new Properties();
            properties.put("healthmanager.tolerant", "true");
            properties.put("backend.1.id", "backend-a");
            properties.put("backend.1.enabled", "true");
            properties.put("backend.1.host", "localhost");
            properties.put("backend.1.port", String.valueOf(wireMockRule.port()));
            properties.put("backend.1.probePath", "/");
            properties.put("director.1.id", "director-1");
            properties.put("director.1.backends", properties.getProperty("backend.1.id"));
            properties.put("director.1.enabled", "true");
            properties.put("action.1.id", "proxy-1");
            properties.put("action.1.enabled", "true");
            properties.put("action.1.type", ActionConfiguration.TYPE_PROXY);
            properties.put("action.1.director", properties.getProperty("director.1.id"));
            properties.put("route.100.id", "route-1");
            properties.put("route.100.enabled", "true");
            properties.put("route.100.match", "request.uri ~ \".*index.html.*\"");
            properties.put("route.100.action", properties.getProperty("action.1.id"));
            properties.put("healthmanager.tolerant", "true");
            // configure resets all listeners configurations
            server.configureAtBoot(new PropertiesConfigurationStore(properties));
            server.addListener(NetworkListenerConfiguration.withDefault("localhost", 0));
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(resp.getStatusLine()).isEqualTo("HTTP/1.1 503 Service Unavailable\r\n");
                assertThat(resp.getBodyString()).isEqualTo("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """);
            }
            assertThat(server.getBackendHealthManager().getBackendStatus(key).getStatus()).isSameAs(COLD); // no troubles for new connections
            TestUtils.waitForCondition(() -> ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get() == 0, 10);
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get()).isZero();
        }
    }

    @ParameterizedTest(name = "useCache={0}")
    @ValueSource(booleans = {true, false})
    void connectionResetByPeer(boolean useCache) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache, false);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(resp.getStatusLine()).isEqualTo("HTTP/1.1 503 Service Unavailable\r\n");
                assertThat(resp.getBodyString()).isEqualTo("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """);
            }
            assertThat(server.getBackendHealthManager().getBackendStatus(key).getStatus()).isSameAs(COLD); // no troubles for new connections
            TestUtils.waitForCondition(() -> ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get() == 0, 10);
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get()).isZero();
        }
    }

}
