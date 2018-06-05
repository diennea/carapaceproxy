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
package nettyhttpproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.utils.RawHttpClient;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ChunckedEncodingRequestsTest {

    private static final String TEST_DATA
        = "4\r\nWiki\r\n"
        + "5\r\npedia\r\n"
        + "E\r\n in\r\n\r\nchunks.\r\n"
        + "0\r\n" // last content
        + "\r\n";

    private static final String TEST_DATA_ABORTED
        = "4\r\nWiki\r\n"
        + "5\r\npe";
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testSimple() throws Exception {

        wireMockRule.stubFor(
            post(urlEqualTo("/index.html")).
                withRequestBody(equalTo("Wikipedia in\r\n"
                    + "\r\n"
                    + "chunks."))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                    .executeRequest("POST /index.html HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + TEST_DATA).toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientAbortsUpload() throws Exception {

        wireMockRule.stubFor(
            post(urlEqualTo("/index.html")).
                withRequestBody(equalTo("Wikipedia in\r\n"
                    + "\r\n"
                    + "chunks."))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("it <b>works</b> !!"))
        );

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                    .executeRequest("POST /index.html HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + TEST_DATA).toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));

                client
                    .sendRequest("POST /index.html HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + TEST_DATA_ABORTED);
            }

            // proxy server is not broker after aborted client
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client
                    .executeRequest("POST /index.html HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + TEST_DATA).toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            System.out.println("stats:"+epstats);
            return epstats.getTotalConnections().intValue() == 2
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

}
