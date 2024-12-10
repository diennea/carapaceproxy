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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.backends.BackendHealthStatus.Status.COLD;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.List;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class UnreachableBackendTest {

    @Parameters(name = "useCache = {0}")
    public static Iterable<Boolean> data() {
        return List.of(true, false);
    }

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private final boolean useCache;

    public UnreachableBackendTest(boolean useCache) {
        this.useCache = useCache;
    }

    @Test
    public void testWithUnreachableBackend() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        int dummyport = wireMockRule.port();
        wireMockRule.stop();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """, resp.getBodyString());
            }
            assertSame(BackendHealthStatus.Status.DOWN, server.getBackendHealthManager().getBackendStatus(key).getStatus());
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @Test
    public void testEmptyResponse() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
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
            assertTrue(port > 0);
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """, resp.getBodyString());
            }
            assertSame(COLD, server.getBackendHealthManager().getBackendStatus(key).getStatus()); // no troubles for new connections
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @Test
    public void testConnectionResetByPeer() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """, resp.getBodyString());
            }
            assertSame(COLD, server.getBackendHealthManager().getBackendStatus(key).getStatus()); // no troubles for new connections
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @Test
    public void testNonHttpResponseThenClose() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
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
            server.configureAtBoot(new PropertiesConfigurationStore(properties));
            server.addListener(NetworkListenerConfiguration.withDefault("localhost", 0));
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("""
                        <html>
                            <body>
                                Service Unavailable
                            </body>
                        </html>
                        """, resp.getBodyString());
            }
            assertSame(COLD, server.getBackendHealthManager().getBackendStatus(key).getStatus());
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }
}
