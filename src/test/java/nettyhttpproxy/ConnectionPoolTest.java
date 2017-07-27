package nettyhttpproxy;

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
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import org.apache.commons.io.IOUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class ConnectionPoolTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(18081);

    @Test
    @Ignore
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("ok")));

        int port = 1234;
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port(), false);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", port, mapper);) {
            server.start();
            stats = server.getConnectionsManager().getStats();

            assertNull(stats.getEndpointStats(key));
            // pipe
            assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);

            EndpointStats epstats = stats.getEndpointStats(key);
            assertNotNull(epstats);
            assertEquals(1, epstats.getTotalConnections().intValue());
            assertEquals(0, epstats.getActiveConnections().intValue());
            assertEquals(1, epstats.getOpenConnections().intValue());
            assertEquals(1, epstats.getTotalRequests().intValue());

            assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);

            for (int i = 0; i < 10; i++) {
                assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
                TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);
//                assertEquals(2, epstats.getTotalConnections().intValue());
//                assertEquals(0, epstats.getActiveConnections().intValue());
//                assertEquals(2, epstats.getOpenConnections().intValue());
                assertEquals(i + 3, epstats.getTotalRequests().intValue());
            }

        }

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }
}
