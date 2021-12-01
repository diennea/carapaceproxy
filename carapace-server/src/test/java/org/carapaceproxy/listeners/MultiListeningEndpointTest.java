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
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URL;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import static org.junit.Assert.assertEquals;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class MultiListeningEndpointTest {

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

        int port = 1234;
        int port2 = 1235;
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", port, mapper, tmpDir.newFolder());) {
            server.addListener(new NetworkListenerConfiguration("localhost", port2));
            server.start();

            // proxy
            {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?redir").toURI(), "utf-8");
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
            }

            {
                String s = IOUtils.toString(new URL("http://localhost:" + port2 + "/index.html?redir").toURI(), "utf-8");
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
            }

        }

    }
}
