package org.carapaceproxy.listeners;

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
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import org.carapaceproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.InetAddress;
import java.security.cert.X509Certificate;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import org.carapaceproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

        String nonLocalhost = InetAddress.getLocalHost().getCanonicalHostName();

        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

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

            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, certificate, "testproxy", STATIC));

            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0,
                    true, false, null, nonLocalhost /* default */,
                    null, null));

            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().endsWith("it <b>works</b> !!"));
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("acert2: " + cert.getSerialNumber());
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

    @Test
    public void testchooseCertificate() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {

            server.addCertificate(new SSLCertificateConfiguration("other", "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.example.com", "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("www.example.com", "cert", "pwd", STATIC));

            // client requests bad SNI, bad default in listener
            assertNull(server.getListeners().chooseCertificate("no", "no-default"));

            // client requests SNI, bad default in listener
            assertEquals("other", server.getListeners().chooseCertificate("other", "no-default").getId());

            assertEquals("www.example.com", server.getListeners().chooseCertificate("unkn-other", "www.example.com").getId());
            // client without SNI
            assertEquals("www.example.com", server.getListeners().chooseCertificate(null, "www.example.com").getId());
            // exact match
            assertEquals("www.example.com", server.getListeners().chooseCertificate("www.example.com", "no-default").getId());
            // wildcard
            assertEquals("*.example.com", server.getListeners().chooseCertificate("test.example.com", "no-default").getId());
        }

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {

            // full wildcard
            server.addCertificate(new SSLCertificateConfiguration("*", "cert", "pwd", STATIC));

            assertEquals("*", server.getListeners().chooseCertificate(null, "www.example.com").getId());
            assertEquals("*", server.getListeners().chooseCertificate("www.example.com", null).getId());
            assertEquals("*", server.getListeners().chooseCertificate(null, null).getId());
            assertEquals("*", server.getListeners().chooseCertificate("", null).getId());
            assertEquals("*", server.getListeners().chooseCertificate(null, "").getId());
        }
    }
}
