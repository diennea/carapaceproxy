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
package org.carapaceproxy.listeners;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static reactor.netty.http.HttpProtocol.HTTP11;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLSession;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
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
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost /* default */, DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().contains("it <b>works</b> !!"));
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("acert2: " + cert.getSerialNumber());
            }
        }
    }

    @Test
    public void testChooseCertificate() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {

            server.addCertificate(new SSLCertificateConfiguration("other", null, "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.example.com", Set.of("example.com", "*.example2.com"), "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("www.example.com", null, "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.qatest.pexample.it", Set.of("qatest.pexample.it"), "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.pexample.it", Set.of("qatest2.pexample.it"), "cert", "pwd", STATIC));


            // client requests bad SNI, bad default in listener
            assertNull(chooseCert(server, "no", "no-default"));

            assertEquals("*.qatest.pexample.it", chooseCertId(server, "test2.qatest.pexample.it", "no-default"));
            // client requests SNI, bad default in listener
            assertEquals("other", chooseCertId(server, "other", "no-default"));

            assertEquals("www.example.com", chooseCertId(server, "unkn-other", "www.example.com"));
            // client without SNI
            assertEquals("www.example.com", chooseCertId(server, null, "www.example.com"));
            // exact match
            assertEquals("www.example.com", chooseCertId(server, "www.example.com", "no-default"));
            // wildcard
            assertEquals("*.example.com", chooseCertId(server, "test.example.com", "no-default"));
            // san
            assertEquals("*.example.com", chooseCertId(server, "example.com", "no-default"));
            assertEquals("*.example.com", chooseCertId(server, "test.example2.com", "no-default"));

            // full wildcard
            server.addCertificate(new SSLCertificateConfiguration("*", null, "cert", "pwd", STATIC));
            // full wildcard has not to hide more specific wildcard one
            assertEquals("*.example.com", chooseCertId(server, "test.example.com", "no-default"));
            // san
            assertEquals("*.example.com", chooseCertId(server, "example.com", "no-default"));
            assertEquals("*.example.com", chooseCertId(server, "test.example2.com", "no-default"));

            // more specific wildcard
            server.addCertificate(new SSLCertificateConfiguration("*.test.example.com", null, "cert", "pwd", STATIC));
            // more specific wildcard has to hide less specific one (*.example.com)
            assertEquals("*.test.example.com", chooseCertId(server, "pippo.test.example.com", "no-default"));
            // san
            assertEquals("*.example.com", chooseCertId(server, "example.com", "no-default"));
            assertEquals("*.example.com", chooseCertId(server, "test.example2.com", "no-default"));
        }

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {

            // full wildcard
            server.addCertificate(new SSLCertificateConfiguration("*", null, "cert", "pwd", STATIC));

            assertEquals("*", chooseCertId(server, null, "www.example.com"));
            assertEquals("*", chooseCertId(server, "www.example.com", null));
            assertEquals("*", chooseCertId(server, null, null));
            assertEquals("*", chooseCertId(server, "", null));
            assertEquals("*", chooseCertId(server, null, ""));
        }
    }

    private static SSLCertificateConfiguration chooseCert(final HttpProxyServer server, final String sniHostname, final String defaultCertificate) {
        return CertificatesUtils.chooseCertificate(server.getListeners().getCurrentConfiguration(), sniHostname, defaultCertificate);
    }

    private static String chooseCertId(final HttpProxyServer server, final String sniHostname, final String defaultCertificate) {
        final var certificate = chooseCert(server, sniHostname, defaultCertificate);
        assertNotNull(certificate);
        return certificate.getId();
    }

    @Test
    public void testTLSVersion() throws Exception {
        String nonLocalhost = InetAddress.getLocalHost().getCanonicalHostName();
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true, false);

        // TLS 1.3 support checking
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of("TLSv1.3"),
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().contains("it <b>works</b> !!"));
                SSLSession session = client.getSSLSocket().getSession();
                assertEquals("TLSv1.3", session.getProtocol());
            }
        }

        // default ssl protocol version support checking
        for (String proto : DEFAULT_SSL_PROTOCOLS) {
            try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
                server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
                server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of(proto),
                        128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
                server.start();
                int port = server.getLocalPort();
                try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    assertTrue(resp.toString().contains("it <b>works</b> !!"));
                    SSLSession session = client.getSSLSocket().getSession();
                    assertEquals(proto, session.getProtocol());
                }
            }
        }
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost,
                    DEFAULT_SSL_PROTOCOLS,
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(resp.toString().contains("it <b>works</b> !!"));
                SSLSession session = client.getSSLSocket().getSession();
                assertTrue(DEFAULT_SSL_PROTOCOLS.contains(session.getProtocol()));
            }
        }

        // wrong ssl protocol version checking
        TestUtils.assertThrows(ConfigurationNotValidException.class, () -> {
            try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
                server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
                server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of("TLSvWRONG"),
                        128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            }
        });
    }
}
