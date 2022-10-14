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
import io.netty.handler.codec.http.HttpHeaderNames;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;

/**
 * @author enrico.olivelli
 */
public class ChunkedEncodingResponseTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testSimpleChunckedResponseNoCache() throws Exception {
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
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));

                resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
            }
            assertEquals(0, server.getCache().getCacheSize());
        }
    }

    @Test
    public void testSimpleChunckedResponseWithCache() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));

                resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.contains("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
            }
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }


    @Test
    public void testChunkedHttp10() throws Exception {
        wireMockRule.stubFor(
                get(urlEqualTo("/index.html")).
                        willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody("it <b>works</b> !!"))
        );
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            server.getCurrentConfiguration().setHttp10BackwardCompatibilityEnabled(true);

            CloseableHttpClient httpclient = HttpClients.createDefault();
            HttpGet request = new HttpGet("http://localhost:" + port + "/index.html");
            request.setProtocolVersion(HttpVersion.HTTP_1_1);
            HttpResponse httpresponse = httpclient.execute(request);

            assertEquals("chunked",
                    httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.TRANSFER_ENCODING)).getValue());
            assertNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.CONTENT_LENGTH)));

            server.getCache().getStats().resetCacheMetrics();
            server.getCache().clear();
            assertEquals(0, server.getCache().getCacheSize());
            assertEquals(0, server.getCache().getStats().getHits());

            request.setProtocolVersion(HttpVersion.HTTP_1_0);
            httpresponse = httpclient.execute(request);

            assertNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.TRANSFER_ENCODING)));
            assertNotNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.CONTENT_LENGTH)));
            assertNull(httpresponse.getFirstHeader("X-cached"));

            //File must be in cache
            assertEquals(1, server.getCache().getCacheSize());

            CloseableHttpClient httpclient2 = HttpClients.createDefault();

            request.setProtocolVersion(HttpVersion.HTTP_1_1);
            httpresponse = httpclient2.execute(request);

            //Response must come from cache
            assertEquals(1, server.getCache().getStats().getHits());

            //Http 1.1 chunked encoding
            assertEquals("chunked",
                    httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.TRANSFER_ENCODING)).getValue());
            assertNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.CONTENT_LENGTH)));
            assertNotNull(httpresponse.getFirstHeader("X-cached"));


            request.setProtocolVersion(HttpVersion.HTTP_1_0);
            httpresponse = httpclient2.execute(request);

            //Response must come from cache
            assertEquals(2, server.getCache().getStats().getHits());
            //Http 1.0 no chunked encoding
            assertNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.TRANSFER_ENCODING)));
            assertNotNull(httpresponse.getFirstHeader(String.valueOf(HttpHeaderNames.CONTENT_LENGTH)));
            assertNotNull(httpresponse.getFirstHeader("X-cached"));

        }
    }


}
