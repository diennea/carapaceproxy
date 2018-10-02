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
package nettyhttpproxy.backends;

import nettyhttpproxy.utils.TestEndpointMapper;
import nettyhttpproxy.utils.TestUtils;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class UnreachableBackendTest {

    @Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {true /* useCache = true */}, {false /* useCache = false */}
        });
    }
    
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private boolean useCache = false;

    public UnreachableBackendTest(boolean useCache) {
        this.useCache = useCache;
    }

    @Test
    public void testWithUnreachableBackend() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        int dummyport = wireMockRule.port();
        wireMockRule.stop();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper);) {
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }
            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

        }
    }

    @Test
    public void testEmptyResponse() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.newFolder());) {
            Properties properties = new Properties();
            properties.put("connectionsmanager.stuckrequesttimeout", "100"); // ms
            properties.put("connectionsmanager.idletimeout", "2000"); // ms
            // configure resets all listeners configurations
            server.configure(new PropertiesConfigurationStore(properties));
            server.addListener(new NetworkListenerConfiguration("localhost", 0));
            server.setMapper(mapper);
            assertEquals(100, server.getCurrentConfiguration().getStuckRequestTimeout());
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }
            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

        }
    }

    @Test
    public void testConnectionResetByPeer() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper);) {
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }
            assertFalse(server.getBackendHealthManager().isAvailable(key.toBackendId()));
            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

        }
    }

    @Test
    public void testNonHttpResponseThenClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        int dummyport = wireMockRule.port();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.newFolder());) {
            Properties properties = new Properties();
            properties.put("connectionsmanager.stuckrequesttimeout", "100"); // ms
            properties.put("connectionsmanager.idletimeout", "2000"); // ms
            server.configure(new PropertiesConfigurationStore(properties));
            server.addListener(new NetworkListenerConfiguration("localhost", 0));
            server.setMapper(mapper);
            assertEquals(100, server.getCurrentConfiguration().getStuckRequestTimeout());
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }
            assertFalse(server.getBackendHealthManager().isAvailable(key.toBackendId()));
            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

        }
    }

}
