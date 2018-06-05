package nettyhttpproxy;

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
import nettyhttpproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class RawClientTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

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
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
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
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
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
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
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
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
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
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                System.out.println("s:" + s);
                assertTrue(s.equals("it <b>works</b> !!"));

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
                assertTrue(s.equals("it <b>works</b> !!"));

                String s2 = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                System.out.println("s4:" + s2);
                assertTrue(s.equals("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            TestUtils.waitForCondition(() -> {
                stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
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

}
