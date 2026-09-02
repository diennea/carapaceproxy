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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author enrico.olivelli
 */
public class ChunkedEncodingResponseIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @Test
    void simpleChunkedResponseNoCache() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
                assertThat(resp.getBodyString()).isEqualTo("it <b>works</b> !!");

                resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertThat(resp.toString()).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
                assertThat(resp.getBodyString()).isEqualTo("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
            }
            assertThat(server.getCache().getCacheSize()).isZero();
        }
    }

    @Test
    void simpleChunkedResponseWithCache() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            resetCache(server);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
                assertThat(resp.getBodyString()).isEqualTo("it <b>works</b> !!");

                resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertThat(resp.toString()).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
                assertThat(resp.getBodyString()).isEqualTo("it <b>works</b> !!");
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertThat(s).contains("""
                        12\r
                        it <b>works</b> !!\r
                        0\r
                        \r
                        """);
            }
            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isEqualTo(2);
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }


    @ParameterizedTest
    @MethodSource("parametersForChunkedHttp10Test")
    void chunkedHttp(final HttpVersion httpVersion, final boolean inCache) throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
            server.start();
            int port = server.getLocalPort();
            server.getCurrentConfiguration().setHttp10BackwardCompatibilityEnabled(true);
            resetCache(server);

            try (CloseableHttpClient client = HttpClients.createDefault()) {
                if (inCache) {
                    HttpGet request = new HttpGet("http://localhost:" + port + "/index.html");
                    request.setProtocolVersion(httpVersion);
                    client.execute(request);
                    TestUtils.waitForCondition(() -> server.getCache().getCacheSize() > 0, 10);
                }

                HttpGet request = new HttpGet("http://localhost:" + port + "/index.html");
                request.setProtocolVersion(httpVersion);
                HttpResponse response = client.execute(request);

                if (Objects.equals(httpVersion, HttpVersion.HTTP_1_0)) {
                    assertThat(response.getFirstHeader(TRANSFER_ENCODING.toString())).isNull();
                    assertThat(response.getFirstHeader(CONTENT_LENGTH.toString())).isNotNull();
                } else {
                    assertThat(response.getFirstHeader(TRANSFER_ENCODING.toString()).getValue()).isEqualTo("chunked");
                    assertThat(response.getFirstHeader(CONTENT_LENGTH.toString())).isNull();
                }
                if (inCache) {
                    assertThat(response.getFirstHeader("X-cached")).isNotNull();
                    assertThat(server.getCache().getStats().getHits()).isOne();
                } else {
                    assertThat(response.getFirstHeader("X-cached")).isNull();
                    assertThat(server.getCache().getStats().getHits()).isZero();
                }
            }

        }
    }

    private static void resetCache(final HttpProxyServer server) {
        server.getCache().reloadConfiguration(server.getCurrentConfiguration());
        server.getCache().getStats().resetCacheMetrics();
        server.getCache().clear();
        assertThat(server.getCache().getCacheSize()).isZero();
        assertThat(server.getCache().getStats().getHits()).isZero();
        assertThat(server.getCache().getStats().getMisses()).isZero();
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
