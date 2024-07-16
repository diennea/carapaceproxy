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
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.CoreMatchers.startsWithIgnoringCase;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Date;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * NOTE: some of these tests are heavily dependent from wiremock stub creation,
 * that could take from 500ms to 1000ms or more, depending on various factors.
 * Executing all tests in this class will hide
 * the issue because the first test to be executed (and probably warm up wiremock) is testHandleExpiresFromServer,
 * that is not affected by a delay of 1000ms.
 * <p>
 * In order to fix the issue test should be refactored to start wiremock
 * (and by the way, HttpProxyServer) in a @Before rule
 */
public class CacheExpireTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testHandleExpiresFromServer() throws Exception {
        Date expire = new Date(System.currentTimeMillis() + 60000 * 2);
        String formatted = HttpUtils.formatDateHeader(expire); // ex Wed, 01 Dec 2021 08:11:39 GMT

        stubFor(get(urlEqualTo("/index-with-expire.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withHeader("Expires", formatted)
                        .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCurrentConfiguration().setRequestCompressionEnabled(false);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("""
                        GET /index-with-expire.html HTTP/1.1\r
                        Host: localhost\r
                        \r
                        """);
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("""
                        GET /index-with-expire.html HTTP/1.1\r
                        Host: localhost\r
                        \r
                        """);
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        hasItem(startsWithIgnoringCase("X-Cached")),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            assertEquals(18, server.getCache().getStats().getDirectMemoryUsed());
            assertEquals(0, server.getCache().getStats().getHeapMemoryUsed());
        }
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

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            final String expires;
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("""
                        GET /index-no-expire.html HTTP/1.1\r
                        Host: localhost\r
                        \r
                        """);
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: "))
                ));
                expires = resp.getHeaderLines().stream().filter(h -> h.startsWith("expires: ")).findFirst().orElseThrow();
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("""
                        GET /index-no-expire.html HTTP/1.1\r
                        Host: localhost\r
                        \r
                        """);
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        hasItem(startsWithIgnoringCase("X-Cached")),
                        hasItem(startsWith(expires))
                ));
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(1, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testDoNotCacheExpiredContent() throws Exception {
        Date expire = new Date(System.currentTimeMillis() - 1000);
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

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(2, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testExpireContentOnGet() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();

            long startts = System.currentTimeMillis();
            Date expire = new Date(startts + 5_000);
            String formatted = HttpUtils.formatDateHeader(expire);

            stubFor(get(urlEqualTo("/index-with-expire.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withHeader("Expires", formatted)
                            .withBody("it <b>works</b> !!"))
            );

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());

            Thread.sleep(5_000);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                // asking for an expired content will make it expire immediately
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(2, server.getCache().getStats().getMisses());
        }
    }

    @Test
    public void testExpireContentWithoutGet() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            server.getCache().getStats().resetCacheMetrics();
            server.getCache().getInnerCache().setVerbose(true);

            long startts = System.currentTimeMillis();
            Date expire = new Date(startts + 2000);
            String formatted = HttpUtils.formatDateHeader(expire);

            stubFor(get(urlEqualTo("/index-with-expire.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                            .withHeader("Expires", formatted)
                            .withBody("it <b>works</b> !!"))
            );

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index-with-expire.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("it <b>works</b> !!"));
                resp.getHeaderLines().forEach(h -> System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines(), allOf(
                        not(hasItem(startsWithIgnoringCase("X-Cached"))),
                        hasItem(startsWithIgnoringCase("Expires: " + formatted))
                ));
            }

            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
            TestUtils.waitForCondition(() -> {
                server.getCache().getInnerCache().evict();
                return server.getCache().getCacheSize() == 0;
            }, 10);
        }
    }
}
