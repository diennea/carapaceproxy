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
import nettyhttpproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Collections;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import org.apache.commons.io.IOUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class SimpleHTTPProxyTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html?redir"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            // debug
            {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?debug").toURI(), "utf-8");
                System.out.println("s:" + s);
            }

            // not found
            try {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?not-found").toURI(), "utf-8");
                System.out.println("s:" + s);
                fail();
            } catch (FileNotFoundException ok) {
            }

            // proxy
            {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?redir").toURI(), "utf-8");
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
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
    public void testSsl() throws Exception {

        HttpUtils.overideJvmWideHttpsVerifier();

        String certificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        String cacertificate = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html?redir"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true,
                certificate, "changeit",
                cacertificate, "changeit", null, false));
            server.start();
            int port = server.getLocalPort();

            // debug
            {
                String s = IOUtils.toString(new URL("https://localhost:" + port + "/index.html?debug").toURI(), "utf-8");
                System.out.println("s:" + s);
            }

            // not found
            try {
                String s = IOUtils.toString(new URL("https://localhost:" + port + "/index.html?not-found").toURI(), "utf-8");
                System.out.println("s:" + s);
                fail();
            } catch (FileNotFoundException ok) {
            }

            // proxy
            {
                String s = IOUtils.toString(new URL("https://localhost:" + port + "/index.html?redir").toURI(), "utf-8");
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
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
    public void testEndpointDown() throws Exception {

        int badPort = TestUtils.getFreePort();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", badPort);
        EndpointKey key = new EndpointKey("localhost", badPort);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            HttpUtils.ResourceInfos result = HttpUtils.downloadFromUrl(new URL("http://localhost:" + port + "/index.html"),
                new ByteArrayOutputStream(), Collections.singletonMap("return_errors", "true"));
            assertEquals(500, result.responseCode);
//            String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8");
//            System.out.println("s:" + s);

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 0
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }
}
