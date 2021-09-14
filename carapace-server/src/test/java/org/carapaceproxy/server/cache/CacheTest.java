package org.carapaceproxy.server.cache;

/*
 * Licensed to Diennea S.r.l. under one or more contributor license agreements. See the NOTICE file distributed with this work for additional information regarding copyright ownership. Diennea S.r.l.
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions and limitations under the License.
 *
 */
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.cache.ContentsCache.CACHE_CONTROL_CACHE_DISABLED_VALUES;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import io.netty.handler.codec.http.HttpHeaderNames;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class CacheTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testServeFromCache() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            long startTs = System.currentTimeMillis();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());

            List<Map<String, Object>> inspect = server.getCache().inspectCache();
            System.out.println("inspect: " + inspect);
            assertThat(inspect.size(), is(1));
            assertThat(inspect.get(0).get("method"), is("GET"));
            assertThat(inspect.get(0).get("host"), is("localhost"));
            assertThat(inspect.get(0).get("uri"), is("/index.html"));
            assertThat(inspect.get(0).get("cacheKey"), is("GET | localhost | /index.html"));
            assertThat(inspect.get(0).get("heapSize"), is(not(0)));
            assertThat(inspect.get(0).get("directSize"), is(not(0)));
            assertThat(inspect.get(0).get("totalSize"), is(not(0)));
            assertTrue((long) inspect.get(0).get("creationTs") >= startTs);
            assertTrue((long) inspect.get(0).get("expiresTs") >= startTs + ContentsCache.DEFAULT_TTL);
            assertThat(inspect.get(0).get("hits"), is(2));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testNotServeFromCacheIfCachableButClientsDisablesCache() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    // Ctrl-F5
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testBootSslRelativeCertificatePath() throws Exception {

        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, false, null, "localhost", null, null));
            server.start();
        }
    }

    @Test
    @Parameters({"true", "false"})
    public void testServeFromCacheSsl(boolean cacheDisabledForSecureRequestsWithoutPublic) throws Exception {

        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, false, null, "localhost", null, null));

            RuntimeServerConfiguration currentConfiguration = server.getCurrentConfiguration();
            currentConfiguration.setCacheDisabledForSecureRequestsWithoutPublic(cacheDisabledForSecureRequestsWithoutPublic);
            server.getCache().reloadConfiguration(currentConfiguration);

            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached only whether cacheDisabledForSecureRequestsWithoutPublic is false
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> {
                        System.out.println("HEADER LINE :" + h);
                    });
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> {
                        System.out.println("HEADER LINE :" + h);
                    });
                    assertEquals(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")), !cacheDisabledForSecureRequestsWithoutPublic);
                }
            }
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            int expected = cacheDisabledForSecureRequestsWithoutPublic ? 0 : 1;
            assertEquals(expected, server.getCache().getCacheSize());
            assertEquals(expected, server.getCache().getStats().getHits());
            assertEquals(expected, server.getCache().getStats().getMisses());
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached due to cache-control: public
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached"))); // cached due to cache-control: public header presence in second request
                }
            }
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached due to cache-control: public
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age=3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age = 3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // never cached due to cache-control: max-age=0
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(0, server.getCache().getStats().getMisses());

            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = server.getConnectionsManager().getStats().getEndpointStats(key);
                return epstats.getTotalConnections().intValue() >= 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);

            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
        }
    }

    @Test
    public void testServeFromCacheChunked() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("12\r\n"
                            + "it <b>works</b> !!\r\n"
                            + "0\r\n\r\n"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("12\r\n"
                            + "it <b>works</b> !!\r\n"
                            + "0\r\n\r\n"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testServeFromCacheWithConnectionClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                resp.getHeaderLines().forEach(h -> {
                    System.out.println("HEADER LINE :" + h);
                });
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("12\r\n"
                            + "it <b>works</b> !!\r\n"
                            + "0\r\n\r\n"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("12\r\n"
                            + "it <b>works</b> !!\r\n"
                            + "0\r\n\r\n"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testNotCachableResourceWithQueryString() throws Exception {
        stubFor(get(urlEqualTo("/index.html?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(0, server.getCache().getStats().getMisses());
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testImagesCachableWithQueryString() throws Exception {
        stubFor(get(urlEqualTo("/index.png?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testNoCacheResponse() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            for (String noCacheValue : CACHE_CONTROL_CACHE_DISABLED_VALUES) {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.CACHE_CONTROL + "", noCacheValue)
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(2, server.getCache().getStats().getMisses());
            }

            // multiple cache-control values
            {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.CACHE_CONTROL + "", "no-cache, no-store")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(2, server.getCache().getStats().getMisses());
            }

            // cache-control value with spaces
            {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.CACHE_CONTROL + "", "max-age  = 0")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(2, server.getCache().getStats().getMisses());
            }

            // cache-control value caseInsensitive
            {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.CACHE_CONTROL + "", "No-CacHe")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(2, server.getCache().getStats().getMisses());
            }

            // pragma value caseInsensitive
            {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.PRAGMA + "", "No-CacHe")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(2, server.getCache().getStats().getMisses());
            }

            // no cache-control set
            {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(1, server.getCache().getCacheSize());
                assertEquals(1, server.getCache().getStats().getHits());
                assertEquals(1, server.getCache().getStats().getMisses());
            }

            ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() >= 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);

            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
        }
    }

    @Test
    public void testNoCacheRequest() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            for (String noCacheValue : CACHE_CONTROL_CACHE_DISABLED_VALUES) {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: " + noCacheValue + "\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: " + noCacheValue + "\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(0, server.getCache().getStats().getMisses());
            }

            // multiple cache-control values
            {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache, no-store\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache, no-store\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(0, server.getCache().getStats().getMisses());
            }

            // cache-control value with spaces
            {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: max-age  = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: max-age  = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(0, server.getCache().getStats().getMisses());
            }

            // cache-control value caseInsensitive
            {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(0, server.getCache().getStats().getMisses());
            }

            // pragma value caseInsensitive
            {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nPragma: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nPragma: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(0, server.getCache().getCacheSize());
                assertEquals(0, server.getCache().getStats().getHits());
                assertEquals(0, server.getCache().getStats().getMisses());
            }

            // no cache-control set
            {
                stubFor(get(urlEqualTo("/index.html"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.html").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.html").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
                assertNotNull(stats.getEndpoints().get(key));
                assertEquals(1, server.getCache().getCacheSize());
                assertEquals(1, server.getCache().getStats().getHits());
                assertEquals(1, server.getCache().getStats().getMisses());
            }

            ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() >= 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);

            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
        }
    }

}
