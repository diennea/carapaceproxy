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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Properties;
import org.carapaceproxy.utils.RawHttpClient;
import java.util.Map;
import org.carapaceproxy.api.UseAdminServer;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.resources.ConnectionProvider;

public class ConnectionPoolTest extends UseAdminServer {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    private Properties config;

    private void configureAndStartServer() throws Exception {

        HttpTestUtils.overideJvmWideHttpsVerifier();

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("config.type", "database");
        config.put("db.jdbc.url", "jdbc:herddb:localhost");
        config.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        config.put("aws.accesskey", "accesskey");
        config.put("aws.secretkey", "secretkey");
        startServer(config);

        // Default certificate
        String defaultCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        config.put("certificate.1.hostname", "*");
        config.put("certificate.1.file", defaultCertificate);
        config.put("certificate.1.password", "changeit");

        // Listeners
        config.put("listener.1.host", "localhost");
        config.put("listener.1.port", "8086");
        config.put("listener.1.enabled", "true");
        config.put("listener.1.defaultcertificate", "*");

        // Backends
        config.put("backend.1.id", "localhost");
        config.put("backend.1.enabled", "true");
        config.put("backend.1.host", "localhost");
        config.put("backend.1.port", wireMockRule.port() + "");

        config.put("backend.2.id", "localhost2");
        config.put("backend.2.enabled", "true");
        config.put("backend.2.host", "localhost2");
        config.put("backend.2.port", wireMockRule.port() + "");

        // Default director
        config.put("director.1.id", "*");
        config.put("director.1.backends", "localhost");
        config.put("director.1.enabled", "true");

        // Default route
        config.put("route.100.id", "default");
        config.put("route.100.enabled", "true");
        config.put("route.100.match", "all");
        config.put("route.100.action", "proxy-all");

        // Default connection pool properties
        config.put("connectionsmanager.maxconnectionsperendpoint", "10");
        config.put("connectionsmanager.borrowtimeout", "5000");
        config.put("connectionsmanager.connecttimeout", "10000");
        config.put("connectionsmanager.stuckrequesttimeout", "15000");
        config.put("connectionsmanager.idletimeout", "20000");
        config.put("connectionsmanager.disposetimeout", "50000");

        // Custom connection pool (with defaults)
        config.put("connectionpool.1.id", "localhost");
        config.put("connectionpool.1.domain", "localhost");
        config.put("connectionpool.1.enabled", "true");

        // Custom connection pool (disabled)
        config.put("connectionpool.2.id", "localhost2");
        config.put("connectionpool.2.domain", "localhost2");
        config.put("connectionpool.2.enabled", "false");

        // Custom connection pool
        config.put("connectionpool.3.id", "localhosts");
        config.put("connectionpool.3.domain", "localhost[0-9]");
        config.put("connectionpool.3.maxconnectionsperendpoint", "20");
        config.put("connectionpool.3.borrowtimeout", "21000");
        config.put("connectionpool.3.connecttimeout", "22000");
        config.put("connectionpool.3.stuckrequesttimeout", "23000");
        config.put("connectionpool.3.idletimeout", "24000");
        config.put("connectionpool.3.disposetimeout", "25000");
        config.put("connectionpool.3.enabled", "true");
        changeDynamicConfiguration(config);
    }

    @Test
    public void test() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();

        // connection pools checking
        Map<ConnectionPoolConfiguration, ConnectionProvider> connectionPools = server.getProxyRequestsManager().getConnectionPools();

        assertThat(connectionPools.size(), is(3)); // disabled one excluded

        // default pool
        ConnectionPoolConfiguration defaultPool = new ConnectionPoolConfiguration(
                "*", "*", 10, 5_000, 10_000, 15_000, 20_000, 50_000, true
        );
        {
            ConnectionProvider provider = connectionPools.get(defaultPool);
            assertThat(provider, not(nullValue()));
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);
        }

        // pool with defaults
        ConnectionPoolConfiguration poolWithDefaults = new ConnectionPoolConfiguration(
                "localhost", "localhost", 10, 5_000, 10_000, 15_000, 20_000, 50_000, true
        );
        {
            ConnectionProvider provider = connectionPools.get(poolWithDefaults);
            assertThat(provider, not(nullValue()));
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);
        }

        // custom pool
        ConnectionPoolConfiguration customPool = new ConnectionPoolConfiguration(
                "localhosts", "localhost[0-9]", 20, 21_000, 22_000, 23_000, 24_000, 25_000, true
        );
        {
            ConnectionProvider provider = connectionPools.get(customPool);
            assertThat(provider, not(nullValue()));
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 20);
        }

        try (RawHttpClient client = new RawHttpClient("localhost", port)) {
            String s1 = client.get("/index.html").getBodyString();
            assertEquals("it <b>works</b> !!", s1);
        }

        // connection pool selection
        ProxyRequestsManager.ConnectionsManager connectionsManager = server.getProxyRequestsManager().getConnectionsManager();

        // default providder
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRemoteAddress()).thenReturn(new InetSocketAddress("localhost*", 8086));

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(defaultPool));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);
        }

        // provider with defaults
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRemoteAddress()).thenReturn(new InetSocketAddress("localhost", 8086));

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(poolWithDefaults));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);
        }

        // custom provider
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRemoteAddress()).thenReturn(new InetSocketAddress("localhost3", 8086));

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(customPool));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 20);
        }
    }
}
