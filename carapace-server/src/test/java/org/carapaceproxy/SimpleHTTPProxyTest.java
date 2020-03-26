package org.carapaceproxy;

/*
 * Licensed to Diennea S.r.l. under one or more contributor license agreements. See the NOTICE file distributed with this work for additional information regarding copyright ownership. Diennea S.r.l.
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions and limitations under the License.
 *
 */
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Collections;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
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

            server.addCertificate(new SSLCertificateConfiguration("localhost", certificate, "changeit", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, false, null, "localhost", cacertificate, "changeit"));

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
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
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

    @Test
    public void testHTTP2() throws Exception {
        final Properties props = System.getProperties();
        props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());

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

            server.addCertificate(new SSLCertificateConfiguration("localhost", certificate, "changeit", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, true, false, null, "localhost", cacertificate, "changeit"));

            server.start();
            int port = server.getLocalPort();

            // configure the SSLContext with a TrustManager
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {

                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };
            sslContext.init(new KeyManager[0], new TrustManager[]{trustManager}, new SecureRandom());
            SSLContext.setDefault(sslContext);

            HttpClient client = HttpClient.newBuilder()
                    .version(Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .build();

            // proxy
            HttpRequest request = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create("https://localhost:" + port + "/index.html?redir"))
                    .build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String resBody = response.body();
            System.out.println("RESPONSE proxy: " + resBody);

            // debug
            request = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create("https://localhost:" + port + "/index.html?debug"))
                    .build();
            response = client.send(request, BodyHandlers.ofString());
            resBody = response.body();
            System.out.println("RESPONSE debug: " + resBody);

            // not found
            request = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create("https://localhost:" + port + "/index.html?not-found"))
                    .build();
            response = client.send(request, BodyHandlers.ofString());
            resBody = response.body();
            System.out.println("RESPONSE not-found: " + resBody);

            // POST
//        BodyPublisher requestBody = BodyPublishers
            //                .ofString("{ request body }");
            //        HttpRequest request = HttpRequest.newBuilder()
            //                .POST(requestBody)
            //                .uri(URI.create("http://codefx.org"))
            //                .build(
        }
    }
}
