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

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static reactor.netty.http.HttpProtocol.HTTP11;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.File;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class SSLSNIIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @Test
    void selectCertWithoutSNI() throws Exception {

        String nonLocalhost = InetAddress.getLocalHost().getCanonicalHostName();

        String certificate = TestUtils.deployResource("localhost.p12", tmpDir);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost /* default */, DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertThat(resp.toString()).contains("it <b>works</b> !!");
                X509Certificate cert = (X509Certificate) client.getSSLSocket().getSession().getPeerCertificates()[0];
                System.out.println("acert2: " + cert.getSerialNumber());
            }
        }
    }

    @Test
    void chooseCertificate() throws Exception {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {

            server.addCertificate(new SSLCertificateConfiguration("other", null, "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.example.com", Set.of("example.com", "*.example2.com"), "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("www.example.com", null, "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.qatest.pexample.it", Set.of("qatest.pexample.it"), "cert", "pwd", STATIC));
            server.addCertificate(new SSLCertificateConfiguration("*.pexample.it", Set.of("qatest2.pexample.it"), "cert", "pwd", STATIC));


            // client requests bad SNI, bad default in listener
            assertThat(chooseCert(server, "no", "no-default")).isNull();

            assertThat(chooseCertId(server, "test2.qatest.pexample.it", "no-default")).isEqualTo("*.qatest.pexample.it");
            // client requests SNI, bad default in listener
            assertThat(chooseCertId(server, "other", "no-default")).isEqualTo("other");

            assertThat(chooseCertId(server, "unkn-other", "www.example.com")).isEqualTo("www.example.com");
            // client without SNI
            assertThat(chooseCertId(server, null, "www.example.com")).isEqualTo("www.example.com");
            // exact match
            assertThat(chooseCertId(server, "www.example.com", "no-default")).isEqualTo("www.example.com");
            // wildcard
            assertThat(chooseCertId(server, "test.example.com", "no-default")).isEqualTo("*.example.com");
            // san
            assertThat(chooseCertId(server, "example.com", "no-default")).isEqualTo("*.example.com");
            assertThat(chooseCertId(server, "test.example2.com", "no-default")).isEqualTo("*.example.com");

            // full wildcard
            server.addCertificate(new SSLCertificateConfiguration("*", null, "cert", "pwd", STATIC));
            // full wildcard has not to hide more specific wildcard one
            assertThat(chooseCertId(server, "test.example.com", "no-default")).isEqualTo("*.example.com");
            // san
            assertThat(chooseCertId(server, "example.com", "no-default")).isEqualTo("*.example.com");
            assertThat(chooseCertId(server, "test.example2.com", "no-default")).isEqualTo("*.example.com");

            // more specific wildcard
            server.addCertificate(new SSLCertificateConfiguration("*.test.example.com", null, "cert", "pwd", STATIC));
            // more specific wildcard has to hide less specific one (*.example.com)
            assertThat(chooseCertId(server, "pippo.test.example.com", "no-default")).isEqualTo("*.test.example.com");
            // san
            assertThat(chooseCertId(server, "example.com", "no-default")).isEqualTo("*.example.com");
            assertThat(chooseCertId(server, "test.example2.com", "no-default")).isEqualTo("*.example.com");
        }

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {

            // full wildcard
            server.addCertificate(new SSLCertificateConfiguration("*", null, "cert", "pwd", STATIC));

            assertThat(chooseCertId(server, null, "www.example.com")).isEqualTo("*");
            assertThat(chooseCertId(server, "www.example.com", null)).isEqualTo("*");
            assertThat(chooseCertId(server, null, null)).isEqualTo("*");
            assertThat(chooseCertId(server, "", null)).isEqualTo("*");
            assertThat(chooseCertId(server, null, "")).isEqualTo("*");
        }
    }

    private static SSLCertificateConfiguration chooseCert(final HttpProxyServer server, final String sniHostname, final String defaultCertificate) {
        return CertificatesUtils.chooseCertificate(server.getListeners().getCurrentConfiguration(), sniHostname, defaultCertificate);
    }

    private static String chooseCertId(final HttpProxyServer server, final String sniHostname, final String defaultCertificate) {
        final var certificate = chooseCert(server, sniHostname, defaultCertificate);
        return certificate.getId();
    }

    @Test
    void tlsVersion() throws Exception {
        String nonLocalhost = InetAddress.getLocalHost().getCanonicalHostName();
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir);
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        // TLS 1.3 support checking
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of("TLSv1.3"),
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertThat(resp.toString()).contains("it <b>works</b> !!");
                SSLSession session = client.getSSLSocket().getSession();
                assertThat(session.getProtocol()).isEqualTo("TLSv1.3");
            }
        }

        // default ssl protocol version support checking
        for (String proto : DEFAULT_SSL_PROTOCOLS) {
            try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
                server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
                server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of(proto),
                        128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
                server.start();
                int port = server.getLocalPort();
                try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    assertThat(resp.toString()).contains("it <b>works</b> !!");
                    SSLSession session = client.getSSLSocket().getSession();
                    assertThat(session.getProtocol()).isEqualTo(proto);
                }
            }
        }
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
            server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost,
                    DEFAULT_SSL_PROTOCOLS,
                    128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient(nonLocalhost, port, true, nonLocalhost)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertThat(resp.toString()).contains("it <b>works</b> !!");
                SSLSession session = client.getSSLSocket().getSession();
                assertThat(DEFAULT_SSL_PROTOCOLS).contains(session.getProtocol());
            }
        }

        // wrong ssl protocol version checking
        assertThatExceptionOfType(ConfigurationNotValidException.class).isThrownBy(() -> {
            try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir)) {
                server.addCertificate(new SSLCertificateConfiguration(nonLocalhost, null, certificate, "testproxy", STATIC));
                server.addListener(new NetworkListenerConfiguration(nonLocalhost, 0, true, null, nonLocalhost, Set.of("TLSvWRONG"),
                        128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            }
        });
    }
}
