package org.carapaceproxy;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URL;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConnectionPoolTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "2")
                        .withBody("ok")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            stats = server.getConnectionsManager().getStats();

            assertNull(stats.getEndpointStats(key));
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("ok", client.get("/index.html").getBodyString().trim());
            }
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);

            EndpointStats epstats = stats.getEndpointStats(key);
            System.out.println("STATS: " + epstats);
            assertNotNull(epstats);
            assertEquals(1, epstats.getTotalConnections().intValue());
            assertEquals(0, epstats.getActiveConnections().intValue());
            assertEquals(1, epstats.getOpenConnections().intValue());
            assertEquals(1, epstats.getTotalRequests().intValue());

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("ok", client.get("/index.html").getBodyString().trim());
            }
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);
            System.out.println("STATS: " + epstats);
            assertEquals(1, epstats.getTotalConnections().intValue());
            assertEquals(0, epstats.getActiveConnections().intValue());
            assertEquals(1, epstats.getOpenConnections().intValue());
            assertEquals(2, epstats.getTotalRequests().intValue());

            for (int i = 0; i < 10; i++) {
                assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
                TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);
                System.out.println("STATS: " + epstats);
                assertEquals(1, epstats.getTotalConnections().intValue());
                assertEquals(0, epstats.getActiveConnections().intValue());
                assertEquals(1, epstats.getOpenConnections().intValue());
                assertEquals(i + 3, epstats.getTotalRequests().intValue());
            }

        }

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }
}
