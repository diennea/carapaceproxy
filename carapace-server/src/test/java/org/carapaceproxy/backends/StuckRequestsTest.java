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
import static org.carapaceproxy.core.ProxyRequest.PROPERTY_URI;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Properties;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.server.mapper.requestmatcher.RegexpRequestMatcher;
import org.carapaceproxy.utils.RawHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class StuckRequestsTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    @Parameters({"true", "false"})
    public void testBackendUnreachableOnStuckRequest(boolean backendsUnreachableOnStuckRequests) throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(5_000)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!"))
        );

        stubFor(get(urlEqualTo("/good-index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        final int theport = wireMockRule.port();
        EndpointKey key = new EndpointKey("localhost", theport);

        StandardEndpointMapper mapper = new StandardEndpointMapper();
        mapper.addBackend(new BackendConfiguration("backend-a", "localhost", theport, "/"));
        mapper.addDirector(new DirectorConfiguration("director-1").addBackend("backend-a"));
        mapper.addAction(new ActionConfiguration("proxy-1", ActionConfiguration.TYPE_PROXY, "director-1", null, -1));
        mapper.addRoute(new RouteConfiguration("route-1", "proxy-1", true, new RegexpRequestMatcher(PROPERTY_URI, ".*index.html.*")));

        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.newFolder());) {
            Properties properties = new Properties();
            properties.put("connectionsmanager.stuckrequesttimeout", "100"); // ms
            properties.put("connectionsmanager.backendsunreachableonstuckrequests", backendsUnreachableOnStuckRequests + "");
            // configure resets all listeners configurations
            server.configureAtBoot(new PropertiesConfigurationStore(properties));
            server.addListener(new NetworkListenerConfiguration("localhost", 0));
            server.setMapper(mapper);
            assertEquals(100, server.getCurrentConfiguration().getStuckRequestTimeout());
            assertEquals(backendsUnreachableOnStuckRequests, server.getCurrentConfiguration().isBackendsUnreachableOnStuckRequests());
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 500 Internal Server Error\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        An internal error occurred\n"
                        + "    </body>        \n"
                        + "</html>\n", resp.getBodyString());
            }

            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
            assertThat((int) ProxyRequestsManager.STUCK_REQUESTS_COUNTER.get() > 0, is(true));

            assertEquals(backendsUnreachableOnStuckRequests, !server.getBackendHealthManager().isAvailable(key.getHostPort()));

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /good-index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                if (backendsUnreachableOnStuckRequests) {
                    assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                    assertEquals("<html>\n"
                            + "    <body>\n"
                            + "        Service Unavailable\n"
                            + "    </body>\n"
                            + "</html>\n", resp.getBodyString());
                } else {
                    assertEquals("HTTP/1.1 200 OK\r\n", resp.getStatusLine());
                    assertEquals("it <b>works</b> !!", resp.getBodyString());
                }
            }
        }
    }
}
