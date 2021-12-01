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

import org.carapaceproxy.utils.TestEndpointMapper;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.carapaceproxy.utils.CarapaceLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
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
                assertTrue(s.endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));

                resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("12\r\n"
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
                assertTrue(s.endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));

                resp = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                System.out.println("s:" + resp);
                assertTrue(resp.toString().endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
                assertTrue(resp.getBodyString().equals("it <b>works</b> !!"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                        .executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("12\r\n"
                        + "it <b>works</b> !!\r\n"
                        + "0\r\n\r\n"));
            }
            assertEquals(1, server.getCache().getCacheSize());
            assertEquals(2, server.getCache().getStats().getHits());
            assertEquals(1, server.getCache().getStats().getMisses());
        }
    }
}
