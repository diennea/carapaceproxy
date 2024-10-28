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
package org.carapaceproxy.server.cache;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.cache.ContentsCache.CACHE_CONTROL_CACHE_DISABLED_VALUES;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static reactor.netty.http.HttpProtocol.HTTP11;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WireMockTest
public class CacheTest {

    @TempDir
    File tmpDir;

    @Test
    public void testServeFromCache(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            long startTs = System.currentTimeMillis();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());

            List<Map<String, Object>> inspect = server.getCache().inspectCache();
            System.out.println("inspect: " + inspect);
            assertThat(inspect.size(), is(1));
            final var cache = inspect.getFirst();
            assertThat(cache.get("method"), is("GET"));
            assertThat(cache.get("scheme"), is("http"));
            assertThat(cache.get("host"), is("localhost"));
            assertThat(cache.get("uri"), is("/index.html"));
            assertThat(cache.get("cacheKey"), is("http | GET | localhost | /index.html"));
            assertThat(cache.get("heapSize"), is(not(0)));
            assertThat(cache.get("directSize"), is(not(0)));
            assertThat(cache.get("totalSize"), is(not(0)));
            assertTrue((long) cache.get("creationTs") >= startTs);
            assertTrue((long) cache.get("expiresTs") >= startTs + ContentsCache.DEFAULT_TTL);
            assertThat(cache.get("hits"), is(2));
        }
    }

    @Test
    public void testNotServeFromCacheIfCachableButClientsDisablesCache(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    // Ctrl-F5
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }

    }

    @Test
    public void testBootSslRelativeCertificatePath(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS,
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11.name())));
            server.start();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testServeFromCacheSsl(final boolean cacheDisabledForSecureRequestsWithoutPublic, final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11.name())));

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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertEquals(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")), !cacheDisabledForSecureRequestsWithoutPublic);
                }
            }

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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached"))); // cached due to cache-control: public header presence in second request
                }
            }
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            assertThat(server.getCache().inspectCache().get(0).get("scheme"), is("https"));
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached due to cache-control: public
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age=3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age = 3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            assertThat(server.getCache().inspectCache().get(0).get("scheme"), is("https"));
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // never cached due to cache-control: max-age=0
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(0, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testServeFromCacheWithRequestProtocol(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        int httpPort = 1234;
        int httpsPort = 1235;

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", httpsPort, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11.name())));
            server.addListener(new NetworkListenerConfiguration("localhost", httpPort));
            server.start();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpsPort, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            server.getCache().clear();

            try (RawHttpClient client = new RawHttpClient("localhost", httpsPort, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
        }
    }

    @Test
    public void testServeFromCacheChunked(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testServeFromCacheWithConnectionClose(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        EndpointKey key = new EndpointKey("localhost", wmRuntimeInfo.getHttpPort());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testNotCachableResourceWithQueryString(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/index.html?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        EndpointKey key = new EndpointKey("localhost", wmRuntimeInfo.getHttpPort());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
            }

            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(0, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testImagesCachableWithQueryString(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/index.png?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        EndpointKey key = new EndpointKey("localhost", wmRuntimeInfo.getHttpPort());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testNoCacheResponse(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        EndpointKey key = new EndpointKey("localhost", wmRuntimeInfo.getHttpPort());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            for (String noCacheValue : CACHE_CONTROL_CACHE_DISABLED_VALUES) {
                stubFor(get(urlEqualTo("/index.png?_nocache"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader(HttpHeaderNames.CACHE_CONTROL.toString(), noCacheValue)
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
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
                                .withHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache, no-store")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
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
                                .withHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "max-age  = 0")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
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
                                .withHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "No-CacHe")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
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
                                .withHeader(HttpHeaderNames.PRAGMA.toString(), "No-CacHe")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));

                server.getCache().getStats().resetCacheMetrics();
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
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
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.png?_nocache").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
                assertEquals(1, server.getCache().getCacheSize());
                assertEquals(1, server.getCache().getStats().getHits());
                assertEquals(1, server.getCache().getStats().getMisses());
            }
        }
    }

    @Test
    public void testNoCacheRequest(final WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wmRuntimeInfo.getHttpPort(), true);
        EndpointKey key = new EndpointKey("localhost", wmRuntimeInfo.getHttpPort());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: " + noCacheValue + "\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                EndpointStats epstats = server.getProxyRequestsManager().getEndpointStats(key);
                assertNotNull(epstats);
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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache, no-store\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: max-age  = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
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
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nPragma: No-CacHe\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                    assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
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
                    assertTrue(s.contains("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String s = client.get("/index.html").toString();
                    System.out.println("s:" + s);
                    assertTrue(s.contains("it <b>works</b> !!"));
                }
                assertEquals(1, server.getCache().getCacheSize());
                assertEquals(1, server.getCache().getStats().getHits());
                assertEquals(1, server.getCache().getStats().getMisses());
            }
        }
    }
}
