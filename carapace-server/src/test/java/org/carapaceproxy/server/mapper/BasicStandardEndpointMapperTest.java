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
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.StaticContentsManager;
import static org.carapaceproxy.server.StaticContentsManager.CLASSPATH_RESOURCE;
import org.carapaceproxy.server.certiticates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.config.URIRequestMatcher;
import org.carapaceproxy.utils.TestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 * @author enrico.olivelli
 */
public class BasicStandardEndpointMapperTest {

    @Rule
    public WireMockRule backend1 = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

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
        mapper.addAction(new ActionConfiguration("static-custom", ActionConfiguration.TYPE_STATIC, null, CLASSPATH_RESOURCE + "/test-static-page.html", 200));

        mapper.addRoute(new RouteConfiguration("route-1", "proxy-1", true, new URIRequestMatcher(".*index.html.*")));
        mapper.addRoute(new RouteConfiguration("route-1b", "cache-1", true, new URIRequestMatcher(".*index2.html.*")));
        mapper.addRoute(new RouteConfiguration("route-1c", "all-1", true, new URIRequestMatcher(".*index3.html.*")));
        mapper.addRoute(new RouteConfiguration("route-2-not-found", "not-found-custom", true, new URIRequestMatcher(".*notfound.html.*")));
        mapper.addRoute(new RouteConfiguration("route-3-error", "error-custom", true, new URIRequestMatcher(".*error.html.*")));
        mapper.addRoute(new RouteConfiguration("route-4-static", "static-custom", true, new URIRequestMatcher(".*static.html.*")));
        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            stats = server.getConnectionsManager().getStats();
            {
                // proxy on director 1
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }

            {
                // cache on director 2
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index2.html").toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }

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

    @Test
    public void testAlwaysServeStaticContent() throws Exception {

        stubFor(get(urlEqualTo("/seconda.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                configuration.put("backend.1.id", "foo");
                configuration.put("backend.1.host", "localhost");
                configuration.put("backend.1.port", backend1.port() + "");
                configuration.put("backend.1.enabled", "true");

                configuration.put("director.1.id", "*");
                configuration.put("director.1.backends", "*");
                configuration.put("director.1.enabled", "true");

                configuration.put("listener.1.host", "0.0.0.0");
                configuration.put("listener.1.port", "1425");
                configuration.put("listener.1.ssl", "false");
                configuration.put("listener.1.enabled", "true");

                configuration.put("route.10.id", "default");
                configuration.put("route.10.enabled", "true");
                configuration.put("route.10.match", "all");
                configuration.put("route.10.action", "proxy-all");

                configuration.put("action.1.id", "serve-static");
                configuration.put("action.1.enabled", "true");
                configuration.put("action.1.type", "static");
                configuration.put("action.1.file", CLASSPATH_RESOURCE + "/test-static-page.html");
                configuration.put("action.1.code", "200");
                configuration.put("route.8.id", "static-page");
                configuration.put("route.8.enabled", "true");
                configuration.put("route.8.match", "regexp .*index.*");
                configuration.put("route.8.action", "serve-static");
                PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
                server.configureAtBoot(config);
            }

            server.start();

            {
                String url = "http://localhost:" + server.getLocalPort() + "/index.html";
                String s = IOUtils.toString(new URL(url).toURI(), "utf-8");
                assertEquals("Test static page", s);
            }
            {

                String url = "http://localhost:" + server.getLocalPort() + "/seconda.html";
                String s = IOUtils.toString(new URL(url).toURI(), "utf-8");
                assertEquals("it <b>works</b> !!", s);
            }

        }
    }

    @Test
    public void testServeACMEChallengeToken() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            final String tokenName = "test-token";
            final String tokenData = "test-token-data-content";
            DynamicCertificatesManager dynamicCertificateManager = mock(DynamicCertificatesManager.class);
            when(dynamicCertificateManager.getChallengeToken(matches(tokenName))).thenReturn(tokenData);
            server.setDynamicCertificateManager(dynamicCertificateManager);

            Properties configuration = new Properties();
            configuration.put("backend.1.id", "foo");
            configuration.put("backend.1.host", "localhost");
            configuration.put("backend.1.port", backend1.port() + "");
            configuration.put("backend.1.enabled", "true");

            configuration.put("director.1.id", "*");
            configuration.put("director.1.backends", "*");
            configuration.put("director.1.enabled", "true");

            configuration.put("listener.1.host", "0.0.0.0");
            configuration.put("listener.1.port", "1425");
            configuration.put("listener.1.ssl", "false");
            configuration.put("listener.1.enabled", "true");

            configuration.put("route.10.id", "default");
            configuration.put("route.10.enabled", "true");
            configuration.put("route.10.match", "all");
            configuration.put("route.10.action", "proxy-all");

            configuration.put("action.1.id", "serve-static");
            configuration.put("action.1.enabled", "true");
            configuration.put("action.1.type", "static");
            configuration.put("action.1.file", CLASSPATH_RESOURCE + "/test-static-page.html");
            configuration.put("action.1.code", "200");
            configuration.put("route.8.id", "static-page");
            configuration.put("route.8.enabled", "true");
            configuration.put("route.8.match", "regexp .*index.*");
            configuration.put("route.8.action", "serve-static");
            PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
            server.configureAtBoot(config);

            server.start();

            // Test existent token
            String url = "http://localhost:" + server.getLocalPort() + "/.well-known/acme-challenge/" + tokenName;
            String s = IOUtils.toString(new URL(url).toURI(), "utf-8");
            assertEquals(tokenData, s);

            // Test not existent token
            try {
                url = "http://localhost:" + server.getLocalPort() + "/.well-known/acme-challenge/not-existent-token";
                IOUtils.toString(new URL(url).toURI(), "utf-8");
                fail();
            } catch (Throwable t) {
                assertTrue(t instanceof FileNotFoundException);
            }

        }
    }

    @Test
    public void testCustomAndDebbugingHeaders() throws Exception {
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

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            Properties configuration = new Properties();
            configuration.put("backend.1.id", "b1");
            configuration.put("backend.1.host", "localhost");
            configuration.put("backend.1.port", backend1.port() + "");
            configuration.put("backend.1.enabled", "true");
            configuration.put("backend.2.id", "b2");
            configuration.put("backend.2.host", "localhost");
            configuration.put("backend.2.port", backend1.port() + "");
            configuration.put("backend.2.enabled", "true");

            configuration.put("director.1.id", "d1");
            configuration.put("director.1.backends", "b1");
            configuration.put("director.1.enabled", "true");
            configuration.put("director.2.id", "d2");
            configuration.put("director.2.backends", "b2");
            configuration.put("director.2.enabled", "true");

            configuration.put("listener.1.host", "0.0.0.0");
            configuration.put("listener.1.port", "1425");
            configuration.put("listener.1.ssl", "false");
            configuration.put("listener.1.enabled", "true");

            configuration.put("route.1.id", "r1");
            configuration.put("route.1.enabled", "true");
            configuration.put("route.1.match", "regexp .*index\\.html");
            configuration.put("route.1.action", "addHeaders");

            configuration.put("action.1.id", "addHeaders");
            configuration.put("action.1.enabled", "true");
            configuration.put("action.1.type", "cache");
            configuration.put("action.1.director", "d1");
            configuration.put("action.1.headers", "h1,h2,h5,h6");

            configuration.put("route.2.id", "r2");
            configuration.put("route.2.enabled", "true");
            configuration.put("route.2.match", "regexp .*index2\\.html");
            configuration.put("route.2.action", "addHeader2");

            configuration.put("action.2.id", "addHeader2");
            configuration.put("action.2.enabled", "true");
            configuration.put("action.2.type", "proxy");
            configuration.put("action.2.director", "d2");
            configuration.put("action.2.headers", "h2,h3,h4");

            configuration.put("route.3.id", "r3");
            configuration.put("route.3.enabled", "true");
            configuration.put("route.3.match", "regexp .*index3\\.html");
            configuration.put("route.3.action", "serve-static");

            configuration.put("action.3.id", "serve-static");
            configuration.put("action.3.enabled", "true");
            configuration.put("action.3.type", "static");
            configuration.put("action.3.file", CLASSPATH_RESOURCE + "/test-static-page.html");
            configuration.put("action.3.code", "200");
            configuration.put("action.3.headers", "h1");

            // Custom headers
            configuration.put("header.1.id", "h1");
            configuration.put("header.1.name", "custom-header-1");
            configuration.put("header.1.value", "header-1-value; header-1-value2;header-1-value3");
            configuration.put("header.2.id", "h2");
            configuration.put("header.2.name", "custom-header-2");
            configuration.put("header.2.value", "header-2-value");
            configuration.put("header.3.id", "h3");
            configuration.put("header.3.name", "custom-header-3"); // test headers merge (default add-mode)
            configuration.put("header.3.value", "header-3-overridden-value");
            configuration.put("header.4.id", "h4");
            configuration.put("header.4.name", "custom-header-3"); // test headers merge
            configuration.put("header.4.value", "header-4-overridden-value");
            configuration.put("header.5.id", "h5");
            configuration.put("header.5.name", "Content-Type"); // test reset headers
            configuration.put("header.5.value", "text/custom-text");
            configuration.put("header.5.mode", "set");
            configuration.put("header.6.id", "h6");
            configuration.put("header.6.name", "Transfer-Encoding"); // test remove headers
            configuration.put("header.6.mode", "remove");

            // To enable debugging header "Mapping-Path"
            configuration.put("mapper.debug", "true");
            configuration.put("mapper.debug.name", "DebugHeaderCustomName");

            PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
            server.configureAtBoot(config);
            server.start();

            int port = server.getLocalPort();
            {
                URLConnection conn = new URL("http://localhost:" + port + "/index.html").openConnection();
                assertEquals("header-1-value; header-1-value2;header-1-value3", conn.getHeaderField("custom-header-1"));
                assertEquals("header-2-value", conn.getHeaderField("custom-header-2"));
                // header mode-set
                assertEquals("text/custom-text", conn.getHeaderField("Content-Type"));
                assertFalse(conn.getHeaderFields().toString().contains("text/html"));
                // header mode-remove: for this action doesn't exist
                assertNull(conn.getHeaderField("Transfer-Encoding"));

                // debugging header "Routing-Path"
                assertEquals("r1;addHeaders;d1;b1", conn.getHeaderField("DebugHeaderCustomName"));
            }
            {
                URLConnection conn = new URL("http://localhost:" + port + "/index2.html").openConnection();
                assertNull(conn.getHeaderField("custom-header-1"));
                assertEquals("header-2-value", conn.getHeaderField("custom-header-2"));
                // in this action is text/html as normal
                assertTrue(conn.getHeaderFields().toString().contains("text/html"));
                // custom-header-3 values have been merged (default mode-add)
                assertTrue(conn.getHeaderFields().toString().contains("header-4-overridden-value"));
                assertTrue(conn.getHeaderFields().toString().contains("header-3-overridden-value"));
                // in this action still exists
                assertNotNull(conn.getHeaderField("Transfer-Encoding"));

                // debugging header "Routing-Path"
                assertEquals("r2;addHeader2;d2;b2", conn.getHeaderField("DebugHeaderCustomName"));
            }
            {
                URLConnection conn = new URL("http://localhost:" + port + "/index3.html").openConnection();
                assertEquals("header-1-value; header-1-value2;header-1-value3", conn.getHeaderField("custom-header-1"));
            }
        }
    }

    @Test
    public void testActionRedirect() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            Properties configuration = new Properties();
            configuration.put("listener.1.host", "0.0.0.0");
            configuration.put("listener.1.port", "1425");
            configuration.put("listener.1.ssl", "false");
            configuration.put("listener.1.enabled", "true");

            // redirect to same domain/uri but with https
            configuration.put("route.1.id", "r1");
            configuration.put("route.1.enabled", "true");
            configuration.put("route.1.match", "regexp .*index\\.html");
            configuration.put("route.1.action", "a1");
            configuration.put("action.1.id", "a1");
            configuration.put("action.1.enabled", "true");
            configuration.put("action.1.type", "redirect");
            configuration.put("action.1.code", "301");
            configuration.put("action.1.redirect.proto", "https");

            // redirect to absolute domain/uri
            configuration.put("route.2.id", "r2");
            configuration.put("route.2.enabled", "true");
            configuration.put("route.2.match", "regexp .*index2\\.html");
            configuration.put("route.2.action", "a2");
            configuration.put("action.2.id", "a2");
            configuration.put("action.2.enabled", "true");
            configuration.put("action.2.type", "redirect"); // default 302 redirect code
            configuration.put("action.2.redirect.location", "http://foo/index0.html");

            // relative redirect (same domain, different uri)
            configuration.put("route.3.id", "r3");
            configuration.put("route.3.enabled", "true");
            configuration.put("route.3.match", "regexp .*index3\\.html");
            configuration.put("route.3.action", "a3");
            configuration.put("action.3.id", "a3");
            configuration.put("action.3.enabled", "true");
            configuration.put("action.3.type", "redirect");
            configuration.put("action.3.code", "303");
            configuration.put("action.3.redirect.location", "/index0.html");

            // redirect custom
            configuration.put("route.4.id", "r4");
            configuration.put("route.4.enabled", "true");
            configuration.put("route.4.match", "regexp .*index4\\.html");
            configuration.put("route.4.action", "a4");
            configuration.put("action.4.id", "a4");
            configuration.put("action.4.enabled", "true");
            configuration.put("action.4.type", "redirect");
            configuration.put("action.4.code", "307");
            configuration.put("action.4.redirect.proto", "https");
            configuration.put("action.4.redirect.host", "192.0.0.1");
            configuration.put("action.4.redirect.port", "1234");
            configuration.put("action.4.redirect.path", "/indexX.html");

            PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
            server.configureAtBoot(config);
            server.start();

            int port = server.getLocalPort();

            {
                // redirect to same host/uri but with https (default port)
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/index.html").openConnection();
                conn.setInstanceFollowRedirects(false);
                assertEquals("https://0.0.0.0:443/index.html", conn.getHeaderField("Location"));
                assertTrue(conn.getHeaderFields().toString().contains("301 Moved Permanently"));
            }

            {
                // redirect to absolute host:port/uri
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/index2.html").openConnection();
                conn.setInstanceFollowRedirects(false);
                System.out.println("HEADERS: " + conn.getHeaderFields().toString());
                assertEquals("http://foo/index0.html", conn.getHeaderField("Location"));
                assertTrue(conn.getHeaderFields().toString().contains("302 Found"));
            }
            {
                // relative redirect (same host:port, different uri)
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/index3.html").openConnection();
                conn.setInstanceFollowRedirects(false);
                System.out.println("HEADERS: " + conn.getHeaderFields().toString());
                assertEquals("http://0.0.0.0:" + port + "/index0.html", conn.getHeaderField("Location"));
                assertTrue(conn.getHeaderFields().toString().contains("303 See Other"));
            }
            {
                // redirect custom
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/index4.html").openConnection();
                conn.setInstanceFollowRedirects(false);
                System.out.println("HEADERS: " + conn.getHeaderFields().toString());
                assertEquals("https://192.0.0.1:1234/indexX.html", conn.getHeaderField("Location"));
                assertTrue(conn.getHeaderFields().toString().contains("307 Temporary Redirect"));
            }
        }
    }
}
