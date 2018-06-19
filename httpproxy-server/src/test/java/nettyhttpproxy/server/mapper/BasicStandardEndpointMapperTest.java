package nettyhttpproxy.server.mapper;

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
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.server.StaticContentsManager;
import nettyhttpproxy.server.config.ActionConfiguration;
import nettyhttpproxy.server.config.BackendConfiguration;
import nettyhttpproxy.server.config.DirectorConfiguration;
import nettyhttpproxy.server.config.RouteConfiguration;
import nettyhttpproxy.server.config.URIRequestMatcher;
import org.apache.commons.io.IOUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;

/**
 *
 * @author enrico.olivelli
 */
public class BasicStandardEndpointMapperTest {

    @Rule
    public WireMockRule backend1 = new WireMockRule(0);

    @Test
    public void test() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        stubFor(get(urlEqualTo("/index2.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        stubFor(get(urlEqualTo("/index3.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        int backendPort = backend1.port();
        StandardEndpointMapper mapper = new StandardEndpointMapper();

        mapper.addBackend(new BackendConfiguration("backend-a", "localhost", backendPort, "/"));
        mapper.addBackend(new BackendConfiguration("backend-b", "localhost", backendPort, "/"));
        mapper.addDirector(new DirectorConfiguration("director-1").addBackend("backend-a"));
        mapper.addDirector(new DirectorConfiguration("director-2").addBackend("backend-b"));
        mapper.addDirector(new DirectorConfiguration("director-all").addBackend("*")); // all of the known backends
        mapper.addAction(new ActionConfiguration("proxy-1", ActionConfiguration.TYPE_PROXY, "director-1", null, -1));
        mapper.addAction(new ActionConfiguration("cache-1", ActionConfiguration.TYPE_CACHE, "director-2", null, -1));
        mapper.addAction(new ActionConfiguration("all-1", ActionConfiguration.TYPE_CACHE, "director-all", null, -1));

        mapper.addAction(new ActionConfiguration("not-found-custom", ActionConfiguration.TYPE_STATIC, null, StaticContentsManager.DEFAULT_NOT_FOUND, 404));
        mapper.addAction(new ActionConfiguration("error-custom", ActionConfiguration.TYPE_STATIC, null, StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR, 500));
        mapper.addAction(new ActionConfiguration("static-custom", ActionConfiguration.TYPE_STATIC, null, "classpath:/test-static-page.html", 200));

        mapper.addRoute(new RouteConfiguration("route-1", "proxy-1", true, new URIRequestMatcher(".*index.html.*")));
        mapper.addRoute(new RouteConfiguration("route-1b", "cache-1", true, new URIRequestMatcher(".*index2.html.*")));
        mapper.addRoute(new RouteConfiguration("route-1c", "all-1", true, new URIRequestMatcher(".*index3.html.*")));
        mapper.addRoute(new RouteConfiguration("route-2-not-found", "not-found-custom", true, new URIRequestMatcher(".*notfound.html.*")));
        mapper.addRoute(new RouteConfiguration("route-3-error", "error-custom", true, new URIRequestMatcher(".*error.html.*")));
        mapper.addRoute(new RouteConfiguration("route-4-static", "static-custom", true, new URIRequestMatcher(".*static.html.*")));
        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();
            stats = server.getConnectionsManager().getStats();
//            {
//                // proxy on director 1
//                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8");
//                assertEquals("it <b>works</b> !!", s);
//            }
//
//            {
//                // cache on director 2
//                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index2.html").toURI(), "utf-8");
//                assertEquals("it <b>works</b> !!", s);
//            }

            {
                // director "all"
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index3.html").toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }

            try {
                IOUtils.toString(new URL("http://localhost:" + port + "/notfound.html").toURI(), "utf-8");
                fail("expected 404");
            } catch (FileNotFoundException ok) {
            }

            {
                String staticContent = IOUtils.toString(new URL("http://localhost:" + port + "/static.html").toURI(), "utf-8");
                assertEquals("Test static page", staticContent);
            }
            {
                String staticContent = IOUtils.toString(new URL("http://localhost:" + port + "/static.html").toURI(), "utf-8");
                assertEquals("Test static page", staticContent);
            }

            try {
                IOUtils.toString(new URL("http://localhost:" + port + "/error.html").toURI(), "utf-8");
                fail("expected 500");
            } catch (IOException ok) {
            }

            try {
                IOUtils.toString(new URL("http://localhost:" + port + "/notmapped.html").toURI(), "utf-8");
                fail("expected 404");
            } catch (FileNotFoundException ok) {
            }
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }
}
