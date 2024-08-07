package org.carapaceproxy.server.mapper;

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
import static org.carapaceproxy.core.ProxyRequest.PROPERTY_URI;
import static org.junit.Assert.assertEquals;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URL;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.requestmatcher.RegexpRequestMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ForceBackendTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public WireMockRule backend1 = new WireMockRule(0);

    @Test
    public void test() throws Exception {
        stubFor(get(urlEqualTo("/index.html?thedirector=director-2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        stubFor(get(urlEqualTo("/index.html?thebackend=backend-b"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        int backendPort = backend1.port();
        StandardEndpointMapper mapper = new StandardEndpointMapper();
        Properties properties = new Properties();
        properties.put("mapper.forcedirector.parameter", "thedirector");
        properties.put("mapper.forcebackend.parameter", "thebackend");
        mapper.configure(new PropertiesConfigurationStore(properties));
        assertEquals("thedirector", mapper.getForceDirectorParameter());
        assertEquals("thebackend", mapper.getForceBackendParameter());

        mapper.addBackend(new BackendConfiguration("backend-a", "localhost", backendPort, "/"));
        mapper.addBackend(new BackendConfiguration("backend-b", "localhost", backendPort, "/"));
        mapper.addDirector(new DirectorConfiguration("director-1").addBackend("backend-a"));
        mapper.addDirector(new DirectorConfiguration("director-2").addBackend("backend-b"));

        mapper.addAction(new ActionConfiguration("proxy-1", ActionConfiguration.TYPE_PROXY, "director-1", null, -1));

        mapper.addRoute(new RouteConfiguration("route-1", "proxy-1", true, new RegexpRequestMatcher(PROPERTY_URI, ".*index.html.*")));

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            {
                // proxy on director 2
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?thedirector=director-2").toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }
            {
                // proxy on backend 2
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?thebackend=backend-b").toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }

        }
    }
}
