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
package org.carapaceproxy.backends;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

public class RestartEndpointTest {

    // in order to be restartable this must be fixed
    private static int tryDiscoverEmptyPort() {
        try (ServerSocket s = new ServerSocket();) {
            s.bind(null);
            return s.getLocalPort();
        } catch (IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    @RegisterExtension
    public static WireMockExtension wireMockRule = WireMockExtension.newInstance().options(options()
            .bindAddress("localhost")
            .jettyStopTimeout(1L) // questo serve perchè se no allo stop del server i socket restano appesi
            .port(tryDiscoverEmptyPort())).build(); // non possiamo mettere 0 se no al restart wiremock sceglie una altra porta

    @TempDir
    File tmpDir;

    @Test
    public void testClientsSendsRequestOnDownBackendAtSendRequest() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")
                        .withHeader("Content-Length", "it <b>works</b> !!".getBytes(StandardCharsets.UTF_8).length + "")
                ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
//                wireMockRule.stop();
//                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
//                System.out.println("statusline:" + resp.getStatusLine());
//                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
//                assertThat(resp.getHeaderLines(), hasItems("cache-control: no-cache\r\n", "connection: keep-alive\r\n"));
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                // wireMockRule.start();
                // ensure that wiremock started again
                IOUtils.toByteArray(new URL("http://localhost:" + wireMockRule.getPort() + "/index.html"));
                System.out.println("Server at " + "http://localhost:" + wireMockRule.getPort() + "/index.html" + " is UP an running !");

                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
            }
        }
    }

    @Test
    public void testClientsSendsRequestOnDownBackendAtSendRequestWithCache() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")
                        .withHeader("Content-Length", "it <b>works</b> !!".getBytes(StandardCharsets.UTF_8).length + "")
                ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
//                wireMockRule.stop();
//                // content is cached
//                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
//
//                server.getCache().clear();
//
//                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
//                System.out.println("statusline:" + resp.getStatusLine());
//                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
//                assertThat(resp.getHeaderLines(), hasItems("cache-control: no-cache\r\n", "connection: keep-alive\r\n"));
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                // wireMockRule.start();
                // ensure that wiremock started again
                IOUtils.toByteArray(new URL("http://localhost:" + wireMockRule.getPort() + "/index.html"));
                System.out.println("Server at " + "http://localhost:" + wireMockRule.getPort() + "/index.html" + " is UP an running !");

                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
            }
        }
    }

    @Test
    public void testClientsSendsRequestBackendRestart() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")
                        .withHeader("Content-Length", "it <b>works</b> !!".getBytes(StandardCharsets.UTF_8).length + "")
                ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort());
        CarapaceLogger.setLoggingDebugEnabled(true);
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());

//                wireMockRule.stop();
//                wireMockRule.start();
                System.out.println("*********************************************************");
                // ensure that wiremock started again
                IOUtils.toByteArray(new URL("http://localhost:" + wireMockRule.getPort() + "/index.html"));
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
            }
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

}
