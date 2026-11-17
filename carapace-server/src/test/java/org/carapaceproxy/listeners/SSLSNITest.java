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
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static reactor.netty.http.HttpProtocol.HTTP11;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLSession;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesTestUtils;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.shredzone.acme4j.util.KeyPairUtils;

public class SSLSNITest {

    private static final String NO_SNI = null;

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
    public void testSniSelectsCertificateByNameAndSan() throws Exception {
        final Certificate fallback = deployCertificate("fallback.p12");
        final Certificate apex = deployCertificate("apex.p12");
        final Certificate www = deployCertificate("www.p12");

        try (HttpProxyServer server = startSslServer(
                new SSLCertificateConfiguration("example.com", Set.of("shop.example.com", "WWW.example.com", "*.api.example.com"), "apex.p12", "", STATIC),
                // each certificate lists the other's id as a SAN: the id must win in both directions
                new SSLCertificateConfiguration("www.example.com", Set.of("EXAMPLE.com"), "www.p12", "", STATIC))) {
            final int port = server.getLocalPort();

            assertEquals(apex, servedCertificate(port, "shop.example.com"));
            assertEquals(apex, servedCertificate(port, "foo.api.example.com"));
            // a wildcard covers a single label
            assertEquals(fallback, servedCertificate(port, "x.foo.api.example.com"));
            // a certificate id wins over another certificate's SAN, whatever the case
            assertEquals(www, servedCertificate(port, "www.example.com"));
            assertEquals(apex, servedCertificate(port, "example.com"));
            assertEquals(fallback, servedCertificate(port, NO_SNI));
        }
    }

    @Test
    public void testInvalidSanDoesNotBreakListener() throws Exception {
        deployCertificate("fallback.p12");
        final Certificate certificate = deployCertificate("certificate.p12");

        // names Netty rejects: they must be skipped, not fail the whole listener
        try (HttpProxyServer server = startSslServer(
                new SSLCertificateConfiguration("example.com", Set.of("", ".bad.example.com", "*bad.example.com", "ok.example.com"), "certificate.p12", "", STATIC))) {
            assertEquals(certificate, servedCertificate(server.getLocalPort(), "ok.example.com"));
        }
    }

    /**
     * Starts a server with an SSL listener on localhost whose default is a {@code *} certificate on {@code fallback.p12},
     * which the caller must deploy.
     *
     * @param certificates the certificates to add next to the default one
     * @return the started server
     * @throws Exception if the server cannot start
     */
    private HttpProxyServer startSslServer(final SSLCertificateConfiguration... certificates) throws Exception {
        final TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true, false);
        final HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());
        server.addCertificate(new SSLCertificateConfiguration("*", null, "fallback.p12", "", STATIC));
        for (final SSLCertificateConfiguration certificate : certificates) {
            server.addCertificate(certificate);
        }
        server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "*", DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
        server.start();
        return server;
    }

    private Certificate deployCertificate(final String fileName) throws Exception {
        final KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        final Certificate[] chain = CertificatesTestUtils.generateSampleChain(keyPair, false);
        Files.write(tmpDir.getRoot().toPath().resolve(fileName), CertificatesUtils.createKeystore(chain, keyPair.getPrivate()));
        return chain[0];
    }

    private static Certificate servedCertificate(final int port, final String sniHostname) throws Exception {
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, sniHostname)) {
            final SSLSession session = client.getSSLSocket().getSession();
            // the client caches sessions per host and port, whatever the SNI: resuming would hide the selection
            session.invalidate();
            return session.getPeerCertificates()[0];
        }
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
