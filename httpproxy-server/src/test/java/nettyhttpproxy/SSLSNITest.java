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
import nettyhttpproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import nettyhttpproxy.utils.RawHttpClient;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SSLSNITest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testSelectCertWithoutSNI() throws Exception {

        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());
        String certificate2 = TestUtils.deployResource("ia.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {

            server.addCertificate(new SSLCertificateConfiguration("localhost",
                    certificate, "testproxy"));

            server.addCertificate(new SSLCertificateConfiguration("*",
                    certificate2, "testproxy"));

            server.addListener(new NetworkListenerConfiguration("localhost", 0,
                    true, false, null, "localhost" /* default */,
                    null, null));

            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();

            // NO SNI
            try (RawHttpClient client = new RawHttpClient("localhost", port, true, null)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().endsWith("it <b>works</b> !!"));
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("cert: " + cert.getSerialNumber());
            }

            // SNI 'localhost'
            try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().endsWith("it <b>works</b> !!"));
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("cert2: " + cert.getSerialNumber());
            }

            // SNI host sconosciuto
            try (RawHttpClient client = new RawHttpClient("localhost", port, true, "unknow-host")) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().endsWith("it <b>works</b> !!"));
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("cert3: " + cert.getSerialNumber());
            }
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
