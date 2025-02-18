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
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static reactor.netty.http.HttpProtocol.HTTP11;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
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

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            // not found
            try {
                String s = IOUtils.toString(URI.create("http://localhost:" + port + "/index.html?not-found"), StandardCharsets.UTF_8);
                System.out.println("s:" + s);
                fail();
            } catch (FileNotFoundException ok) {
            }

            // proxy
            {
                String s = IOUtils.toString(URI.create("http://localhost:" + port + "/index.html?redir"), StandardCharsets.UTF_8);
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
            }
        }
    }

    @Test
    public void testSsl() throws Exception {

        HttpTestUtils.overrideJvmWideHttpsVerifier();

        String certificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        String caCertificate = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        stubFor(get(urlEqualTo("/index.html?redir"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, certificate, "changeit", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, null, "localhost", DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11), new DefaultChannelGroup(new DefaultEventExecutor())));
            server.start();
            int port = server.getLocalPort();

            // not found
            try {
                String s = IOUtils.toString(URI.create("https://localhost:" + port + "/index.html?not-found"), StandardCharsets.UTF_8);
                System.out.println("s:" + s);
                fail();
            } catch (FileNotFoundException ok) {
            }

            // proxy
            {
                String s = IOUtils.toString(URI.create("https://localhost:" + port + "/index.html?redir"), StandardCharsets.UTF_8);
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
            }
        }
    }

    @Test
    public void testEndpointDown() throws Exception {

        int badPort = TestUtils.getFreePort();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", badPort);
        EndpointKey key = new EndpointKey("localhost", badPort);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            HttpTestUtils.ResourceInfos result = HttpTestUtils.downloadFromUrl(URI.create("http://localhost:" + port + "/index.html").toURL(),
                    new ByteArrayOutputStream(), Collections.singletonMap("return_errors", "true"));
            assertEquals(503, result.responseCode);
        }
    }
}
