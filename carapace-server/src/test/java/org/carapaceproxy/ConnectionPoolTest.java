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
import static org.hamcrest.CoreMatchers.containsString;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URL;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.carapaceproxy.client.EndpointNotAvailableException;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.client.impl.EndpointConnectionImpl;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConnectionPoolTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "2")
                        .withBody("ok")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            stats = server.getConnectionsManager().getStats();

            assertNull(stats.getEndpointStats(key));
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("ok", client.get("/index.html").getBodyString().trim());
            }
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);

            EndpointStats epstats = stats.getEndpointStats(key);
            System.out.println("STATS: " + epstats);
            assertNotNull(epstats);
            assertEquals(1, epstats.getTotalConnections().intValue());
            assertEquals(0, epstats.getActiveConnections().intValue());
            assertEquals(1, epstats.getOpenConnections().intValue());
            assertEquals(1, epstats.getTotalRequests().intValue());

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("ok", client.get("/index.html").getBodyString().trim());
            }
            TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);
            System.out.println("STATS: " + epstats);
            assertEquals(1, epstats.getTotalConnections().intValue());
            assertEquals(0, epstats.getActiveConnections().intValue());
            assertEquals(1, epstats.getOpenConnections().intValue());
            assertEquals(2, epstats.getTotalRequests().intValue());

            for (int i = 0; i < 10; i++) {
                assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
                TestUtils.waitForCondition(TestUtils.NO_ACTIVE_CONNECTION(stats), 100);
                System.out.println("STATS: " + epstats);
                assertEquals(epstats.getTotalConnections().intValue(), 1);
                assertEquals(0, epstats.getActiveConnections().intValue());
                assertEquals(epstats.getOpenConnections().intValue(), 1);
                assertEquals(i + 3, epstats.getTotalRequests().intValue());
            }

        }

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testExhaustConnections() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "2")
                        .withBody("ok")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            Properties props = new Properties();
            props.setProperty("connectionsmanager.maxconnectionsperendpoint", "1");
            props.setProperty("connectionsmanager.borrowtimeout", "1000");

            server.configureAtBoot(new PropertiesConfigurationStore(props));
            server.start();
            stats = server.getConnectionsManager().getStats();
            ConnectionsManagerImpl connectionsManager = (ConnectionsManagerImpl) server.getConnectionsManager();
            int expectedMaxWaitTimeout = connectionsManager.getBorrowTimeout();
            assertEquals(1000, connectionsManager.getBorrowTimeout());
            assertEquals(1, connectionsManager.getConnections().getMaxTotalPerKey());
            assertEquals(1, connectionsManager.getConnections().getMaxIdlePerKey());

            EndpointConnectionImpl connection1 = (EndpointConnectionImpl) connectionsManager.getConnection(key);
            try {
                long _start = System.currentTimeMillis();
                long _end;
                try {
                    connectionsManager.getConnection(key);
                    fail("cannot get more than 1 connection");
                } catch (EndpointNotAvailableException err) {
                    assertThat(err.getMessage(), containsString("Too many"));
                } finally {
                    _end = System.currentTimeMillis();
                }
                long delta = _end - _start;
                assertTrue("ERROR AFTER: " + delta, delta >= expectedMaxWaitTimeout);
            } finally {
                connectionsManager.returnConnection(connection1, "test");
            }
        }

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testMoreClientsThenMaxConnections() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "2")
                        .withBody("ok")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {

            // tewaking configuration
            server.getCurrentConfiguration().setMaxConnectionsPerEndpoint(1);
            server.getCurrentConfiguration().setBorrowTimeout(30_000);
            server.getConnectionsManager().applyNewConfiguration(server.getCurrentConfiguration());
            server.start();
            stats = server.getConnectionsManager().getStats();

            int port = server.getLocalPort();

            ExecutorService threadPool = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> all = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    all.add(threadPool.submit(() -> {
                        try {
                            assertEquals("ok", IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8"));
                        } catch (Exception err) {
                            throw new RuntimeException(err);
                        }
                    }));

                }
                for (Future<?> handle : all) {
                    handle.get();
                }
            } finally {
                threadPool.shutdownNow();
            }
        }

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }
}
