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

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Objects;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static org.junit.Assert.*;

/**
 * @author enrico.olivelli
 */
@RunWith(JUnitParamsRunner.class)
public class ChunkedEncodingResponseTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testSimpleChunkedResponseNoCache() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

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
                assertEquals("it <b>works</b> !!", resp.getBodyString());

                resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
                assertEquals("it <b>works</b> !!", resp.getBodyString());
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
            }
            assertEquals(0, server.getCache().getCacheSize());
        }
    }

    @Test
    public void testSimpleChunkedResponseWithCache() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();
            resetCache(server);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
                assertEquals("it <b>works</b> !!", resp.getBodyString());

                resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
                assertEquals("it <b>works</b> !!", resp.getBodyString());
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """));
            }
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }


    @Test
    @Parameters(method = "parametersForChunkedHttp10Test")
    public void testChunkedHttp(final HttpVersion httpVersion, final boolean inCache) throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();
            server.getCurrentConfiguration().setHttp10BackwardCompatibilityEnabled(true);
            resetCache(server);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                if (inCache) {
                    HttpGet request = new HttpGet("http://localhost:" + port + "/index.html");
                    request.setProtocolVersion(httpVersion);
                    client.execute(request);
                }

                HttpGet request = new HttpGet("http://localhost:" + port + "/index.html");
                request.setProtocolVersion(httpVersion);
                HttpResponse response = client.execute(request);

                if (Objects.equals(httpVersion, HttpVersion.HTTP_1_0)) {
                    assertNull(response.getFirstHeader(TRANSFER_ENCODING.toString()));
                    assertNotNull(response.getFirstHeader(CONTENT_LENGTH.toString()));
                } else {
                    assertEquals("chunked", response.getFirstHeader(TRANSFER_ENCODING.toString()).getValue());
                    assertNull(response.getFirstHeader(CONTENT_LENGTH.toString()));
                }
                if (inCache) {
                    assertNotNull(response.getFirstHeader("X-cached"));
                    assertEquals(1, server.getCache().getStats().getHits());
                } else {
                    assertNull(response.getFirstHeader("X-cached"));
                    assertEquals(0, server.getCache().getStats().getHits());
                }
            }

        }
    }

    private static void resetCache(final HttpProxyServer server) {
        server.getCache().reloadConfiguration(server.getCurrentConfiguration());
        server.getCache().getStats().resetCacheMetrics();
        server.getCache().clear();
        assertEquals(0, server.getCache().getCacheSize());
        assertEquals(0, server.getCache().getStats().getHits());
        assertEquals(0, server.getCache().getStats().getMisses());
    }

    public static Object[] parametersForChunkedHttp10Test() {
        return new Object[] {
                new Object[] { HttpVersion.HTTP_1_0, false },
                new Object[] { HttpVersion.HTTP_1_0, true },
                new Object[] { HttpVersion.HTTP_1_1, false },
                new Object[] { HttpVersion.HTTP_1_1, true },
        };
    }

}
