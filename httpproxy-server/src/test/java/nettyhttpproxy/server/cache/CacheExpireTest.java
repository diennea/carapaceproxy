package nettyhttpproxy.server.cache;

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
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import nettyhttpproxy.*;
import nettyhttpproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CacheExpireTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testHandleExpiresFromServer() throws Exception {

        java.util.Date expire = new java.util.Date(System.currentTimeMillis() + 60000 * 2);
        String formatted = HttpUtils.formatDateHeader(expire);

        stubFor(get(urlEqualTo("/index-with-expire.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withHeader("Expires", formatted)
                .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
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
    public void testHandleExpiresMissingFromServer() throws Exception {

        stubFor(get(urlEqualTo("/index-no-expire.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            String expires;
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-no-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: ")));
                expires = resp.getHeaderLines().stream().filter(h -> h.startsWith("Expires: ")).findFirst().get();
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-no-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith(expires)));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
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
    public void testDoNotCacheExpiredContent() throws Exception {

        java.util.Date expire = new java.util.Date(System.currentTimeMillis() - 1000);
        String formatted = HttpUtils.formatDateHeader(expire);

        stubFor(get(urlEqualTo("/index-with-expire.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withHeader("Expires", formatted)
                .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(2, server.getCache().getStats().getMisses());
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
    public void testExpireContentOnGet() throws Exception {

        java.util.Date expire = new java.util.Date(System.currentTimeMillis() + 1000);
        String formatted = HttpUtils.formatDateHeader(expire);

        stubFor(get(urlEqualTo("/index-with-expire.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withHeader("Expires", formatted)
                .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());

            Thread.sleep(2000);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                // asking for an expired content will make it expire immediately
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(2, server.getCache().getStats().getMisses());
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
    public void testExpireContentWithoutGet() throws Exception {

        java.util.Date expire = new java.util.Date(System.currentTimeMillis() + 1000);
        String formatted = HttpUtils.formatDateHeader(expire);

        stubFor(get(urlEqualTo("/index-with-expire.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withHeader("Expires", formatted)
                .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("X-Cached")));
                assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.startsWith("Expires: " + formatted)));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());

            Thread.sleep(1500);

            server.getCache().runEvictor();

            assertEquals(0, server.getCache().getCacheSize());

        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }
}