package org.carapaceproxy.server.cache;

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
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import org.carapaceproxy.*;
import org.carapaceproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NotModifiedTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testServeFromCacheAnswer304() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                .withHeader("Last-Modified", HttpUtils.formatDateHeader(new java.util.Date(System.currentTimeMillis() - 60000)))
                .withBody("it <b>works</b> !!")
            ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
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
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "If-Modified-Since: " + HttpUtils.formatDateHeader(new java.util.Date(System.currentTimeMillis())) + "\r\n"
                        + "\r\n");
                    assertTrue(resp.getStatusLine().trim().equals("HTTP/1.1 304 Not Modified"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("Expires")));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("Last-Modified")));
                    assertTrue(resp.getBodyString().isEmpty());
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
            return epstats.getTotalConnections().intValue() == 1
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

}
