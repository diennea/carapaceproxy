/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server;

import org.carapaceproxy.core.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.File;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.nio.file.Files;
import org.carapaceproxy.utils.CarapaceLogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author paolo.venturi
 */
public class FullHttpMessageLoggerTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testGetMessageLog() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.getCurrentConfiguration().setAccessLogPath(tmpDir.getRoot().getAbsolutePath() + "/access.log");
            server.getCurrentConfiguration().setAccessLogAdvancedEnabled(true);
            server.getCurrentConfiguration().setAccessLogTimestampFormat("dd-MM-yyyy HH:mm:ss.SSS");
            server.getCurrentConfiguration().setMaxConnectionsPerEndpoint(1);
            server.getFullHttpMessageLogger().reloadConfiguration(server.getCurrentConfiguration());
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }
            }
            Thread.sleep(3000);
            File[] f = new File(tmpDir.getRoot().getAbsolutePath()).listFiles((dir, name) -> name.startsWith("access") && name.endsWith(".full"));
            assertTrue(f.length == 1);
            Files.readAllLines(f[0].toPath()).forEach(l -> System.out.println(l));
        }
    }

    @Test
    public void testPostMessageLog() throws Exception {
        String responseJson = "{\"property\" : \"value\"}";
        stubFor(post(urlEqualTo("/index.html"))
                .willReturn(WireMock.okJson(responseJson)));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.getCurrentConfiguration().setAccessLogPath(tmpDir.getRoot().getAbsolutePath() + "/access.log");
            server.getCurrentConfiguration().setAccessLogAdvancedEnabled(true);
            server.getCurrentConfiguration().setAccessLogAdvancedBodySize(5);
            server.getFullHttpMessageLogger().reloadConfiguration(server.getCurrentConfiguration());
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String body = "{\"values\" : {\"p\" : \"v\"}, \"options\" : {\"o\" : 1}}";
                RawHttpClient.HttpResponse res =
                        client.executeRequest("POST /index.html HTTP/1.1"
                                + "\r\nHost: localhost"
                                + "\r\nConnection: keep-alive"
                                + "\r\nContent-Type: application/json"
                                + "\r\nContent-Length: " + body.length()
                                + "\r\n\r\n"
                                + body
                        );
                String resp = res.getBodyString();
            }
        }
        Thread.sleep(3000);
        File[] f = new File(tmpDir.getRoot().getAbsolutePath()).listFiles((dir, name) -> name.startsWith("access") && name.endsWith(".full"));
        assertTrue(f.length == 1);
        Files.readAllLines(f[0].toPath()).forEach(l -> System.out.println(l));
    }
}
