package org.carapaceproxy;

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
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.carapaceproxy.server.HttpProxyServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import static org.hamcrest.CoreMatchers.containsString;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.utils.CarapaceLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

/**
 *
 * @author enrico.olivelli
 */
public class RawClientTest {

    private static final Logger LOG = Logger.getLogger(RawClientTest.class.getName());

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public TestName testName = new TestName();

    @Before
    public void dumpTestName() throws Exception {
        LOG.log(Level.INFO, "Starting {0}", testName.getMethodName());
    }

    @After
    public void dumpTestNameEnd() throws Exception {
        LOG.log(Level.INFO, "End {0}", testName.getMethodName());
    }

    @Test
    public void testClientsExpectsConnectionClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsExpectsConnectionCloseWithDownEndpoint() throws Exception {

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", 1111);
        EndpointKey key = new EndpointKey("localhost", 1111);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 0
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsSendsRequestAndClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            }

            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsSendsRequestAndCloseOnDownBackend() throws Exception {

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", 1111);
        EndpointKey key = new EndpointKey("localhost", 1111);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            }

            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                assertNotNull(_stats.getEndpoints().get(key));
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 0
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            stats.getEndpoints().values().forEach((EndpointStats st) -> {
                System.out.println("st3:" + st);
            });
            return epstats.getTotalConnections().intValue() == 0
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void clientsKeepAliveSimpleTest() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            // we want to reuse the same connection to the endpoint if the client is using Keep-Alive
            ((ConnectionsManagerImpl) server.getConnectionsManager()).getConnections().setMaxTotalPerKey(1);
            server.start();
            int port = server.getLocalPort();
            assertEquals(1, ((ConnectionsManagerImpl) server.getConnectionsManager()).getConnections().getMaxTotalPerKey());

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);

                try {
                    String s2 = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                    System.out.println("response2: " + s2);
                    fail();
                } catch (IOException ok) {
                }

            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                System.out.println("s3:" + s);
                assertEquals("it <b>works</b> !!", s);

                // this is needed for Travis, that runs with 1 vCPU!
                // without this sleep the connection pool is not able to reuse the connection (this appears to the
                // beheviour or CommonsPool2
                Thread.sleep(1000);

                String s2 = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                System.out.println("s4:" + s2);
                assertEquals("it <b>works</b> !!", s2);
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            TestUtils.waitForCondition(() -> {
                stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("statse:" + st);
                });
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 2
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 1;
            }, 100);
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 2
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void downloadSmallPayloadsTest() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "1")
                        .withBody("a")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                for (int i = 0; i < 1000; i++) {
                    String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
//                    System.out.println("#" + i + " s:" + s);
                    assertEquals("a", s);
                }

            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            System.out.println("epstats:" + epstats);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void endpointKeyTest() throws Exception {
        {
            EndpointKey entryPoint = EndpointKey.make("localhost:8080");
            assertThat(entryPoint.getHost(), is("localhost"));
            assertThat(entryPoint.getPort(), is(8080));
        }
        {
            EndpointKey entryPoint = EndpointKey.make("localhost");
            assertThat(entryPoint.getHost(), is("localhost"));
            assertThat(entryPoint.getPort(), is(0));
        }
    }

    @Test
    public void testManyInflightRequests() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            List<RawHttpClient> clients = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                RawHttpClient client = new RawHttpClient("localhost", port);
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                clients.add(client);
            }

            int i = 0;
            for (RawHttpClient client : clients) {
                client.close();
                i++;
            }
            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testConnectionCloseWhenErrorOnRequest() throws Exception {
         stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            ConnectionsManagerImpl conMan = (ConnectionsManagerImpl) server.getConnectionsManager();
            conMan.getConnections().setMaxTotalPerKey(1);
            server.start();
            int port = server.getLocalPort();
            assertEquals(1, conMan.getConnections().getMaxTotalPerKey());

            conMan.forceErrorOnRequest(true);

            CarapaceLogger.setLoggingDebugEnabled(true);

            stats = server.getConnectionsManager().getStats();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertThat(conMan.getConnections().getNumIdle(), is(0));
                String s = client.get("/index.html").getBodyString();
                System.out.println("s:" + s);
                assertThat(s, containsString("An internal error occurred"));
                assertNotNull(server.getConnectionsManager().getStats().getEndpoints().get(key));
                assertThat(conMan.getConnections().getNumIdle(), is(1));
            }
            assertThat(conMan.getConnections().getNumIdle(), is(1));

            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = stats.getEndpointStats(key);
                System.out.println("stats: " + epstats);
                return epstats.getTotalConnections().intValue() ==  1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            assertThat(conMan.getConnections().getNumIdle(), is(1));
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }
}
