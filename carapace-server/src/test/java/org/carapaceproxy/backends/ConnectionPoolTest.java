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
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Properties;

import org.carapaceproxy.utils.RawHttpClient;

import java.util.Map;

import org.carapaceproxy.api.ConnectionPoolsResource;
import org.carapaceproxy.api.UseAdminServer;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    private Properties config;

    private void configureAndStartServer() throws Exception {

        HttpTestUtils.overrideJvmWideHttpsVerifier();

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
        config.put("connectionsmanager.keepaliveidle", "500");
        config.put("connectionsmanager.keepaliveinterval", "50");
        config.put("connectionsmanager.keepalivecount", "5");

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
        config.put("connectionpool.3.keepaliveidle", "250");
        config.put("connectionpool.3.keepaliveinterval", "25");
        config.put("connectionpool.3.keepalivecount", "2");
        config.put("connectionpool.3.enabled", "true");

        changeDynamicConfiguration(config);
    }

    @Test
    public void NullHostHeaderTest() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        String hostname = "localhost";
        String path = "/index.html";

        //No Http header host
        String response = sendRequest(false, hostname, port, path);
        // no response because NullPointerException occurred before.
        // java.lang.NullPointerException: Cannot invoke "java.lang.CharSequence.length()" because "this.text" is null
        assertFalse(response.contains("it <b>works</b> !!"));

        //Add Http header host
        String response2 = sendRequest(true, hostname, port, path);
        assertTrue(response2.contains("it <b>works</b> !!"));
    }

    public String sendRequest(boolean addHeaderHost, String hostname, int port, String path) throws IOException {

        Socket socket = new Socket(hostname, port);
        OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        StringBuilder sb = new StringBuilder();

        // Send the HTTP request without the Host header
        out.write("GET " + path + " HTTP/1.1\r\n");
        out.write("Connection: close\r\n");
        if (addHeaderHost) {
            out.write("Host: localhost3\r\n");
        }
        out.write("\r\n");
        out.flush();

        // Read the response from the server
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line);
        }

        // Close the socket and streams
        in.close();
        out.close();
        socket.close();

        return sb.toString();
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
                "*", "*", 10, 5_000, 10_000, 15_000, 20_000, 50_000, 500, 50, 5, true, true
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
                "localhost", "localhost", 10, 5_000, 10_000, 15_000, 20_000, 50_000, 500, 50, 5, true, true
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
                "localhosts", "localhost[0-9]", 20, 21_000, 22_000, 23_000, 24_000, 25_000, 250, 25, 2, true, true
        );
        {
            ConnectionProvider provider = connectionPools.get(customPool);
            assertThat(provider, not(nullValue()));
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 20);
        }

        // connection pool selection
        ProxyRequestsManager.ConnectionsManager connectionsManager = server.getProxyRequestsManager().getConnectionsManager();

        // default provider
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRequestHostname()).thenReturn("localhost*");

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(defaultPool));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\n" + HttpHeaderNames.HOST + ": localhost*" + "\r\n\r\n");
                assertEquals("it <b>works</b> !!", resp.getBodyString());
            }
            Map<String, HttpProxyServer.ConnectionPoolStats> stats = server.getConnectionPoolsStats().get(EndpointKey.make("localhost", wireMockRule.port()));
            assertThat(stats.get("*").getTotalConnections(), is(1));
            assertThat(stats.get("localhost"), is(nullValue()));
            assertThat(stats.get("localhosts"), is(nullValue()));
        }

        // provider with defaults
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRequestHostname()).thenReturn("localhost");

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(poolWithDefaults));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 10);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\n" + HttpHeaderNames.HOST + ": localhost" + "\r\n\r\n");
                assertEquals("it <b>works</b> !!", resp.getBodyString());
            }
            Map<String, HttpProxyServer.ConnectionPoolStats> stats = server.getConnectionPoolsStats().get(EndpointKey.make("localhost", wireMockRule.port()));
            assertThat(stats.get("*").getTotalConnections(), is(1));
            assertThat(stats.get("localhost").getTotalConnections(), is(1));
            assertThat(stats.get("localhosts"), is(nullValue()));
        }

        // custom provider
        {
            HttpServerRequest request = mock(HttpServerRequest.class);
            ProxyRequest proxyRequest = mock(ProxyRequest.class);
            when(proxyRequest.getRequest()).thenReturn(request);
            when(proxyRequest.getRequestHostname()).thenReturn("localhost3");

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> res = connectionsManager.apply(proxyRequest);
            assertThat(res.getKey(), is(customPool));
            ConnectionProvider provider = res.getValue();
            Map<SocketAddress, Integer> maxConnectionsPerHost = provider.maxConnectionsPerHost();
            assertThat(maxConnectionsPerHost.size(), is(2));
            maxConnectionsPerHost.values().stream().allMatch(e -> e == 20);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\n" + HttpHeaderNames.HOST + ": localhost3" + "\r\n\r\n");
                assertEquals("it <b>works</b> !!", resp.getBodyString());
            }
            Map<String, HttpProxyServer.ConnectionPoolStats> stats = server.getConnectionPoolsStats().get(EndpointKey.make("localhost", wireMockRule.port()));
            assertThat(stats.get("*").getTotalConnections(), is(1));
            assertThat(stats.get("localhost").getTotalConnections(), is(1));
            assertThat(stats.get("localhosts").getTotalConnections(), is(1));
        }
    }

    @Test
    public void testAPIResource() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();

        try (RawHttpClient client = new RawHttpClient("localhost", port)) {
            String s1 = client.get("/index.html").getBodyString();
            assertEquals("it <b>works</b> !!", s1);
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/connectionpools", credentials);
            TypeReference<HashMap<String, ConnectionPoolsResource.ConnectionPoolBean>> typeRef = new TypeReference<HashMap<String, ConnectionPoolsResource.ConnectionPoolBean>>() {
            };
            Map<String, ConnectionPoolsResource.ConnectionPoolBean> pools = MAPPER.readValue(response.getBodyString(), typeRef);
            assertThat(pools.size(), is(4));

            // default pool
            assertThat(pools.get("*"), is(new ConnectionPoolsResource.ConnectionPoolBean(
                    "*", "*", 10, 5_000, 10_000, 15_000, 20_000, 50_000, 500, 50, 5, true, true, 0
            )));

            // pool with defaults
            assertThat(pools.get("localhost"), is(new ConnectionPoolsResource.ConnectionPoolBean(
                    "localhost", "localhost", 10, 5_000, 10_000, 15_000, 20_000, 50_000, 500, 50, 5, true, true, 1
            )));

            // disabled custom pool
            assertThat(pools.get("localhost2"), is(new ConnectionPoolsResource.ConnectionPoolBean(
                    "localhost2", "localhost2", 10, 5_000, 10_000, 15_000, 20_000, 50_000, 500, 50, 5, true, false, 0
            )));

            // custom pool
            assertThat(pools.get("localhosts"), is(new ConnectionPoolsResource.ConnectionPoolBean(
                    "localhosts", "localhost[0-9]", 20, 21_000, 22_000, 23_000, 24_000, 25_000, 250, 25, 2, true, true, 0
            )));
        }
    }
}
