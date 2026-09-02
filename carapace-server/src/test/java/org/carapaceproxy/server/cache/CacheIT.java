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
import static org.assertj.core.api.Assertions.assertThat;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static reactor.netty.http.HttpProtocol.HTTP11;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class CacheIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @Test
    void serveFromCache() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            long startTs = System.currentTimeMillis();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isEqualTo(2);
            assertThat(server.getCache().getStats().getMisses()).isOne();

            List<Map<String, Object>> inspect = server.getCache().inspectCache();
            System.out.println("inspect: " + inspect);
            assertThat(inspect).hasSize(1);
            assertThat(inspect.getFirst()).containsEntry("method", "GET");
            assertThat(inspect.getFirst()).containsEntry("scheme", "http");
            assertThat(inspect.getFirst()).containsEntry("host", "localhost");
            assertThat(inspect.getFirst()).containsEntry("uri", "/index.html");
            assertThat(inspect.getFirst()).containsEntry("cacheKey", "http | GET | localhost | /index.html");
            // A chunk lands either in a heap or in a direct buffer, never in both, so only the sum is nonzero.
            assertThat((long) inspect.getFirst().get("heapSize") + (long) inspect.getFirst().get("directSize")).isPositive();
            assertThat((long) inspect.getFirst().get("totalSize")).isPositive();
            assertThat((long) inspect.getFirst().get("creationTs")).isGreaterThanOrEqualTo(startTs);
            assertThat((long) inspect.getFirst().get("expiresTs")).isGreaterThanOrEqualTo(startTs + ContentsCache.DEFAULT_TTL);
            assertThat(inspect.getFirst()).containsEntry("hits", 2);
        }
    }

    @Test
    void notServeFromCacheIfCachableButClientsDisablesCache() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    // Ctrl-F5
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: no-cache\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }

    }

    @Test
    void bootSslRelativeCertificatePath() throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir);) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS,
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serveFromCacheSsl(boolean cacheDisabledForSecureRequestsWithoutPublic) throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir);) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));

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
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(!cacheDisabledForSecureRequestsWithoutPublic).isEqualTo(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }

            int expected = cacheDisabledForSecureRequestsWithoutPublic ? 0 : 1;
            assertThat(server.getCache().getCacheSize()).isEqualTo(expected);
            assertThat(server.getCache().getStats().getHits()).isEqualTo(expected);
            assertThat(server.getCache().getStats().getMisses()).isEqualTo(expected);
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached due to cache-control: public
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached")); // cached due to cache-control: public header presence in second request
                }
            }
            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
            assertThat(server.getCache().inspectCache().getFirst()).containsEntry("scheme", "https");
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // cached due to cache-control: public
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age=3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: puBlIc, max-age = 3600\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }
            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
            assertThat(server.getCache().inspectCache().getFirst()).containsEntry("scheme", "https");
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();

            // never cached due to cache-control: max-age=0
            try (RawHttpClient client = new RawHttpClient("localhost", port, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nCache-Control: public, max-age = 0\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
            }
            assertThat(server.getCache().getCacheSize()).isZero();
            assertThat(server.getCache().getStats().getHits()).isZero();
            assertThat(server.getCache().getStats().getMisses()).isZero();
        }
    }

    @Test
    void serveFromCacheWithRequestProtocol() throws Exception {
        TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);
        int httpPort = 1234;
        int httpsPort = 1235;

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir);) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", httpsPort, true, null, "localhost",
                    DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.addListener(NetworkListenerConfiguration.withDefault("localhost", httpPort));
            server.start();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpsPort, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            server.getCache().clear();

            try (RawHttpClient client = new RawHttpClient("localhost", httpsPort, true)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", httpPort)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("it <b>works</b> !!");
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serveFromCacheChunked(boolean connectionClose) throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            final String firstRequest = connectionClose
                    ? "GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    : "GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n";
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest(firstRequest);
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """);
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    System.out.println("s:" + s);
                    assertThat(s).contains("""
                            12\r
                            it <b>works</b> !!\r
                            0\r
                            \r
                            """);
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                }
            }

            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isEqualTo(2);
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }

    @Test
    void notCachableResourceWithQueryString() throws Exception {
        stubFor(get(urlEqualTo("/index.html?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            assertThat(server.getCache().getCacheSize()).isZero();
            assertThat(server.getCache().getStats().getHits()).isZero();
            assertThat(server.getCache().getStats().getMisses()).isZero();
        }
    }

    @Test
    void imagesCachableWithQueryString() throws Exception {
        stubFor(get(urlEqualTo("/index.png?_nocache"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }

    @ParameterizedTest
    @MethodSource("noCacheHeaderCases")
    void noCacheResponse(String headerName, String headerValue) throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();

            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();
            wireMockRule.resetAll();

            stubFor(get(urlEqualTo("/index.png?_nocache"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader(headerName, headerValue)
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withBody("it <b>works</b> !!")));

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }
            assertThat(server.getCache().getCacheSize()).isZero();
            assertThat(server.getCache().getStats().getHits()).isZero();
            assertThat(server.getCache().getStats().getMisses()).isEqualTo(2);
        }
    }

    private static Object[] noCacheHeaderCases() {
        return new Object[]{
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "private"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-store"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "max-age=0"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache, no-store"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "max-age  = 0"},
            new Object[]{HttpHeaderNames.CACHE_CONTROL.toString(), "No-CacHe"},
            new Object[]{HttpHeaderNames.PRAGMA.toString(), "no-cache"},
            new Object[]{HttpHeaderNames.PRAGMA.toString(), "No-CacHe"}
        };
    }

    @Test
    void noCacheResponseCachable() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();
            wireMockRule.resetAll();
            stubFor(get(urlEqualTo("/index.png?_nocache"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withBody("it <b>works</b> !!")));
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.png?_nocache").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }
            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }

    @ParameterizedTest
    @MethodSource("noCacheHeaderCases")
    void noCacheRequest(String headerName, String headerValue) throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.getPort());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();
            wireMockRule.resetAll();
            stubFor(get(urlEqualTo("/index.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withBody("it <b>works</b> !!")));
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n" + headerName + ": " + headerValue + "\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n" + headerName + ": " + headerValue + "\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            EndpointStats epstats = server.getProxyRequestsManager().getEndpointStats(key);
            assertThat(epstats).isNotNull();
            assertThat(server.getCache().getCacheSize()).isZero();
            assertThat(server.getCache().getStats().getHits()).isZero();
            assertThat(server.getCache().getStats().getMisses()).isZero();
        }
    }

    @Test
    void noCacheRequestCachable() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();
            wireMockRule.resetAll();
            stubFor(get(urlEqualTo("/index.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withBody("it <b>works</b> !!")));
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.get("/index.html").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
            }
            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }

}
