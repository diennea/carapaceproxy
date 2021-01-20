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
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.carapaceproxy.server.HttpProxyServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import static org.hamcrest.CoreMatchers.containsString;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.RawHttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

/**
 *
 * @author enrico.olivelli
 */
public class RawClientTest {

    private static final Logger LOG = Logger.getLogger(RawClientTest.class.getName());

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public TestName testName = new TestName();

    @Before
    public void dumpTestName() throws Exception {
        LOG.log(Level.INFO, "Starting {0}", testName.getMethodName());
    }

    @After
    public void dumpTestNameEnd() throws Exception {
        LOG.log(Level.INFO, "End {0}", testName.getMethodName());
    }

    @Test
    public void testClientsExpectsConnectionClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toString();
                System.out.println("s:" + s);
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsExpectsConnectionCloseWithDownEndpoint() throws Exception {

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", 1111);
        EndpointKey key = new EndpointKey("localhost", 1111);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

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

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 0
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsSendsRequestAndClose() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            }

            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testClientsSendsRequestAndCloseOnDownBackend() throws Exception {

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", 1111);
        EndpointKey key = new EndpointKey("localhost", 1111);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            }

            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                assertNotNull(_stats.getEndpoints().get(key));
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 0
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            stats.getEndpoints().values().forEach((EndpointStats st) -> {
                System.out.println("st3:" + st);
            });
            return epstats.getTotalConnections().intValue() == 0
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void clientsKeepAliveSimpleTest() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            // we want to reuse the same connection to the endpoint if the client is using Keep-Alive
            ((ConnectionsManagerImpl) server.getConnectionsManager()).getConnections().setMaxTotalPerKey(1);
            server.start();
            int port = server.getLocalPort();
            assertEquals(1, ((ConnectionsManagerImpl) server.getConnectionsManager()).getConnections().getMaxTotalPerKey());

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);

                try {
                    String s2 = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                    System.out.println("response2: " + s2);
                    fail();
                } catch (IOException ok) {
                }

            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                System.out.println("s3:" + s);
                assertEquals("it <b>works</b> !!", s);

                // this is needed for Travis, that runs with 1 vCPU!
                // without this sleep the connection pool is not able to reuse the connection (this appears to the
                // beheviour or CommonsPool2
                Thread.sleep(1000);

                String s2 = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                System.out.println("s4:" + s2);
                assertEquals("it <b>works</b> !!", s2);
            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            TestUtils.waitForCondition(() -> {
                stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("statse:" + st);
                });
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 2
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 1;
            }, 100);
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 2
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void downloadSmallPayloadsTest() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "1")
                        .withBody("a")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                for (int i = 0; i < 1000; i++) {
                    String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
//                    System.out.println("#" + i + " s:" + s);
                    assertEquals("a", s);
                }

            }

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            System.out.println("epstats:" + epstats);
            return epstats.getTotalConnections().intValue() >= 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void endpointKeyTest() throws Exception {
        {
            EndpointKey entryPoint = EndpointKey.make("localhost:8080");
            assertThat(entryPoint.getHost(), is("localhost"));
            assertThat(entryPoint.getPort(), is(8080));
        }
        {
            EndpointKey entryPoint = EndpointKey.make("localhost");
            assertThat(entryPoint.getHost(), is("localhost"));
            assertThat(entryPoint.getPort(), is(0));
        }
    }

    @Test
    public void testManyInflightRequests() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            List<RawHttpClient> clients = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                RawHttpClient client = new RawHttpClient("localhost", port);
                client.sendRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                clients.add(client);
            }

            int i = 0;
            for (RawHttpClient client : clients) {
                client.close();
                i++;
            }
            TestUtils.waitForCondition(() -> {
                ConnectionsManagerStats _stats = server.getConnectionsManager().getStats();
                if (_stats == null || (_stats.getEndpoints().get(key) == null)) {
                    return false;
                }
                _stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = _stats.getEndpointStats(key);
                return epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

    @Test
    public void testConnectionCloseWhenErrorOnRequest() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            ConnectionsManagerImpl conMan = (ConnectionsManagerImpl) server.getConnectionsManager();
            conMan.getConnections().setMaxTotalPerKey(1);
            server.start();
            int port = server.getLocalPort();
            assertEquals(1, conMan.getConnections().getMaxTotalPerKey());

            conMan.forceErrorOnRequest(true);

            CarapaceLogger.setLoggingDebugEnabled(true);

            stats = server.getConnectionsManager().getStats();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertThat(conMan.getConnections().getNumIdle(), is(0));
                String s = client.get("/index.html").getBodyString();
                System.out.println("s:" + s);
                assertThat(s, containsString("An internal error occurred"));
                assertNotNull(server.getConnectionsManager().getStats().getEndpoints().get(key));
                assertThat(conMan.getConnections().getNumIdle(), is(1));
            }
            assertThat(conMan.getConnections().getNumIdle(), is(1));

            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = stats.getEndpointStats(key);
                System.out.println("stats: " + epstats);
                return epstats.getTotalConnections().intValue() == 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            assertThat(conMan.getConnections().getNumIdle(), is(1));
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

    @Test
    public void testRequestsRealBackend() throws Exception {

        String host = "sviluppo24.sviluppo.dna";
        String baseUri = "/ws/rest/api/";
        String auth =
                "9189A4D2F408FA495665AF6CA8ED47DA56758A3E400F1FE3C35D2034A954BA400E01A15895781073F8AFF7A771EBF1572CD8EAB9853864017DAD5105687FBCC2FD1EF4C52B63C95B28808FAEC045402F4DAC5320A5DDDAF035CB6EF7A99D6C8B5FC9ECA8AA1D3E7EA72FF15517E1461E1698162CF3FC444704F34FA8EDE7DB05FCF49";
//        String auth =
//                "2237A-4989BC148DC4123C1404C77E1164089662FB00EA34F976BF190E4D07B584DFB89BA424E99AE170445C6034F3479FA8BA1797E30649AA05F7DC45FE44AED87F6AB90CFD133E744EA34740E9FB6E4FBD089C37A6CF4BD17B3421202766C5584CA58073D39AF2CD183194EAE33B30DEED5E96DA7DF8E4BF7E61BC23384E939027E1";
        int threads = 100;
        final int requestsPerClient = 200;
        TestEndpointMapper mapper = new TestEndpointMapper(host, 8443);
//        TestEndpointMapper mapper = new TestEndpointMapper("ws-mn1.mag-news.it", 443);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future> futures = new ArrayList<>();
        AtomicInteger countOk = new AtomicInteger();
        AtomicInteger countError = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 43558, mapper, tmpDir.newFolder())) {
            server.getCurrentConfiguration().setMaxConnectionsPerEndpoint(15);
            server.getConnectionsManager().applyNewConfiguration(server.getCurrentConfiguration());
            server.start();
            int port = server.getLocalPort();

            String uri = "v19/contacts/subscribe";
//            String uri = "version";

            try {

                for (int i = 0; i < threads; i++) {
                    System.out.println("OK RUN " + i);
                    futures.add(ex.submit(() -> {

                        try {
                            try (RawHttpClient client = new RawHttpClient("localhost", port, false)) {
//                                client.getSocket().setKeepAlive(true);
                                client.getSocket().setSoTimeout(1000 * 10);
                                for (int rq = 0; rq < requestsPerClient; rq++) {
                                    if (stop.get()) {
                                        throw new RuntimeException("stopped");
                                    }
//                                    if (rq == requestsPerClient - 1) {
//                                        client.getSocket().close();
//                                    }
                                    String body = "{\"values\" : {\"email\" : \"newcontact-" + System.nanoTime() + rq + "@localhost\"}, \"options\" : {\"iddatabase\" : 1}}";
                                    RawHttpClient.HttpResponse response =
                                            client.executeRequest("POST " + baseUri + uri + " HTTP/1.1"
                                                    + "\r\nHost: " + "localhost"
                                                    + "\r\nAuthorization: Bearer " + auth
                                                    + "\r\nAccept: application/json"
                                                    + "\r\nAccept-Encoding: gzip, deflate, br"
                                                    + "\r\nCache-Control: no-cache"
                                                    + "\r\nConnection: keep-alive"
                                                    + "\r\nContent-Type: application/json"
                                                    + "\r\nContent-Length: " + body.length()
                                                    + "\r\n\r\n"
                                                    + body, false
                                            );

//                                            client.executeRequest("GET " + baseUri + uri + " HTTP/1.1"
//                                                    + "\r\nHost: " + host
//                                                    + "\r\nAuthorization: Bearer " + auth
//                                                    + "\r\nConnection: keep-alive"
//                                                    + "\r\n\r\n"
//                                            );
                                    String res = response.getBodyString();
                                    if (response.getStatusLine().contains("200")) {
                                        countOk.incrementAndGet();
                                    } else {
                                        System.out.println("bad response:" + res + "\n" + response);
                                        countError.incrementAndGet();
                                        stop.set(true);
                                    }
//                                    try {
//                                        Thread.sleep(new Random().nextInt(5000));
//                                    } catch (Throwable e) {
//                                        Thread.currentThread().interrupt();
//                                        System.out.println("err while sleeping" + e);
//                                        
//                                    }
                                    
                                }
                            }
                        } catch (Throwable e) {
                            countError.incrementAndGet();
                            System.out.println("Bad err!!" + e);
                            throw new RuntimeException(e);
                        }
                    }));
//                    if (i == 0) {
//                        try {
//                            futures.get(0).get();
//                            System.out.println("DONE FIRST, OK? ");
//                        } catch (Throwable e) {
//                            countError.incrementAndGet();
//                            System.out.println("DONE FIRST");
//                            System.out.println("ERR" + e);
//                            System.out.println(e);
//                        }
//
//                    }
                }

            } finally {
                for (Future future : futures) {
                    try {
                        future.get();
                    } catch (Throwable e) {
                        System.out.println("ERR" + e);
                        System.out.println(e);
                    }
                }
                System.out.println("WAIT FOR TERMINATION");
                ex.shutdown();
                ex.awaitTermination(1, TimeUnit.MINUTES);
            }
        }
        System.out.println("RESULTS: OK " + countOk.get() + ", ERR " + countError.get());
        assertFalse(countError.get() > 0);
    }

    @Test
    public void testRequestsReadTimeoutReal() throws Exception {
        String carapaceNodeHost = "mn-carapace.sviluppo.dna";
        String endpoint = "qapg24ws";
        int carapaceNodePort = 443;
        String host = "mn-carapace.sviluppo.dna";
        String baseUri = "/ws/rest/api/";
        String auth =
                "9189A4D2F408FA495665AF6CA8ED47DA56758A3E400F1FE3C35D2034A954BA400E01A15895781073F8AFF7A771EBF1572CD8EAB9853864017DAD5105687FBCC2FD1EF4C52B63C95B28808FAEC045402F4DAC5320A5DDDAF035CB6EF7A99D6C8B5FC9ECA8AA1D3E7EA72FF15517E1461E1698162CF3FC444704F34FA8EDE7DB05FCF49";

        long requests = 1_000_000;

        int testCase = 3;
        long clients = 100;
        int readTimeoutSeconds = 30;

        switch (testCase) {
            case 0: {
                // get 1 client
                String uri = "version";
                try (RawHttpClient client = new RawHttpClient(carapaceNodeHost, carapaceNodePort, true)) {
                    client.getSocket().setSoTimeout(readTimeoutSeconds * 1_000);
                    for (int i = 0; i < requests; i++) {
                        String res = client.executeRequest("GET " + baseUri + uri + " HTTP/1.1"
                                + "\r\nHost: " + host
                                + "\r\nAuthorization: Bearer " + auth
                                + "\r\nConnection: keep-alive"
                                + "\r\n\r\n"
                        ).getBodyString();
                        System.out.println("RESP: " + res);
                    }
                }
                break;
            }
            case 1: {
                // get multi client
                for (int i = 0; i < clients; i++) {
                    final int thread = i;
                    new Thread(() -> {
                        try (RawHttpClient client = new RawHttpClient(carapaceNodeHost, carapaceNodePort, true)) {
                            client.getSocket().setSoTimeout(readTimeoutSeconds * 1_000);
                            String uri = "version";
                            while (true) {
                                RawHttpClient.HttpResponse res = client.executeRequest("GET " + baseUri + uri + " HTTP/1.1"
                                        + "\r\nHost: " + host
                                        + "\r\nAuthorization: Bearer " + auth
                                        + "\r\nConnection: keep-alive"
                                        + "\r\n\r\n"
                                );
                                String resp = res.getBodyString();
                                System.out.println("Thread " + thread + " RESP: " + resp + "; HEADERS: " + String.join("; ", res.getHeaderLines()));
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION: " + e);
                        }
                    }).start();
                }
                TestUtils.waitForCondition(() -> false, 60 * 60 * 1);
                break;
            }
            case 2: {
                // post 1 client
                String uri = "v19/contacts/subscribe";
                try (RawHttpClient client = new RawHttpClient(carapaceNodeHost, carapaceNodePort, true)) {
                    client.getSocket().setSoTimeout(readTimeoutSeconds * 1_000);
                    for (int i = 0; i < requests; i++) {
                        String body = "{\"values\" : {\"email\" : \"newcontact-" + i + "@localhost\"}, \"options\" : {\"iddatabase\" : 7}}";
                        String res = client.executeRequest("POST " + baseUri + uri + " HTTP/1.1"
                                + "\r\nHost: " + host
                                + "\r\nAuthorization: Bearer " + auth
                                + "\r\nConnection: keep-alive"
                                + "\r\nContent-Type: application/json"
                                + "\r\nContent-Length: " + body.length()
                                + "\r\n\r\n"
                                + body
                        ).getBodyString();
                        System.out.println("RESP: " + res);
                    }
                }
                break;
            }
            case 3: {
                // post multi client
                for (int i = 0; i < clients; i++) {
                    final int thread = i;
                    new Thread(() -> {
                        try (RawHttpClient client = new RawHttpClient(carapaceNodeHost, carapaceNodePort, true)) {
                            client.getSocket().setSoTimeout(readTimeoutSeconds * 1_000);
                            while (true) {
                                String uri = "v19/contacts/subscribe";
                                String body = "{\"values\" : {\"email\" : \"newcontact-" + thread + "@localhost\"}, \"options\" : {\"iddatabase\" : 1}}";
                                RawHttpClient.HttpResponse res =
                                        client.executeRequest("POST " + baseUri + uri + " HTTP/1.1"
                                                + "\r\nHost: " + host
                                                + "\r\nendpoint: " + endpoint
                                                + "\r\nAuthorization: Bearer " + auth
                                                + "\r\nConnection: keep-alive"
                                                + "\r\nContent-Type: application/json"
                                                + "\r\nContent-Length: " + body.length()
                                                + "\r\n\r\n"
                                                + body
                                        );
                                String resp = res.getBodyString();
                                System.out.println("Thread " + thread + " RESP: " + resp + "; HEADERS: " + String.join("; ", res.getHeaderLines()));
                            }
                        } catch (Exception e) {
                            System.out.println("Thread " + thread + " time=" + System.currentTimeMillis() + " EXCEPTION: " + e);
                        }
                    }).start();
                }
                break;
            }
        }

        TestUtils.waitForCondition(() -> false, 60 * 60 * 2); // 2h
    }

    @Test
    public void testRequestsReadTimeout() throws Exception {
        String responseJson = "{\"property\" : \"value\"}";
        stubFor(post(urlEqualTo("/index.html"))
                .willReturn(WireMock.okJson(responseJson)));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            ConnectionsManagerImpl conMan = (ConnectionsManagerImpl) server.getConnectionsManager();
            conMan.getConnections().setMaxTotalPerKey(10);
            server.start();
            int port = server.getLocalPort();
            assertEquals(10, conMan.getConnections().getMaxTotalPerKey());

            stats = server.getConnectionsManager().getStats();

            long clients = 100;
            int maxRequests = 10;
            int readTimeoutSeconds = 30;

            Random rnd = new Random();
            // post multi client
            for (int i = 0; i < clients; i++) {
                final int thread = i;
                new Thread(() -> {
                    while (true) {
                        try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                            client.getSocket().setSoTimeout(readTimeoutSeconds * 1_000);
                            for (int j = 0; j < rnd.nextInt(maxRequests + 1); j++) {
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
                                System.out.println("Thread " + thread + " time=" + System.currentTimeMillis() + " RESP: " + resp + "; HEADERS: " + String.join("; ", res.getHeaderLines()));
                            }
                        } catch (Exception e) {
                            System.out.println("Thread " + thread + " time=" + System.currentTimeMillis() + " EXCEPTION: " + e);
                            System.out.println("EXCEPTION NUM IDLE: " + conMan.getConnections().getNumIdle());
                            System.out.println("EXCEPTION NUM ACTIVE: " + conMan.getConnections().getNumActive());
                            System.out.println("EXCEPTION NUM WAITERS: " + conMan.getConnections().getNumWaiters());
                            fail();
                        }
                    }
                }).start();
            }

            TestUtils.waitForCondition(() -> false, 60 * 60 * 2); // 2h
        }
    }

    @Test
    public void testKeepAliveTimeout() throws Exception {
        RawHttpServer httpServer = new RawHttpServer(new HttpServlet() {
            public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                response.setContentType("text/html");
                PrintWriter out = response.getWriter();
                out.println("it <b>works</b> !!");
            }
        });
        httpServer.setIdleTimeout(5);
        int httpServerPort = httpServer.start();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", httpServerPort);
        EndpointKey key = new EndpointKey("localhost", httpServerPort);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                for (int j = 0; j < 2; j++) {
                    RawHttpClient.HttpResponse res = client.get("/index.html");
                    String resp = res.getBodyString();
                    System.out.println("RESP: " + resp + "; HEADERS: " + String.join("; ", res.getHeaderLines()));
                    Thread.sleep(10_000);
                }
            } catch (Exception e) {
                System.out.println("EXCEPTION: " + e);
            }
        }
    }

    @Test
    public void testEmptyDataFromServer() throws Exception {

        RawHttpServer httpServer = new RawHttpServer(new HttpServlet() {
            public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
//                try {
//                    Thread.sleep(70_000);
////                response.setContentType("text/html");
////                PrintWriter out = response.getWriter();
////                out.println("it <b>works</b> !!");
//                } catch (InterruptedException ex) {
//                    Logger.getLogger(RawClientTest.class.getName()).log(Level.SEVERE, null, ex);
//                }
            }
        });
        //httpServer.setIdleTimeout(5);
        int httpServerPort = httpServer.start();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", httpServerPort);
        EndpointKey key = new EndpointKey("localhost", httpServerPort);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                for (int j = 0; j < 2; j++) {
                    RawHttpClient.HttpResponse res = client.get("/index.html");
                    String resp = res.getBodyString();
                    System.out.println("RESP: " + resp + "; HEADERS: " + String.join("; ", res.getHeaderLines()));
                }
            } catch (Exception e) {
                System.out.println("EXCEPTION: " + e);
            }
        }
    }

    private static class NoDataDummyServer extends ChannelInboundHandlerAdapter {

        public NoDataDummyServer(String host, int port) throws InterruptedException {
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                Bootstrap b = new Bootstrap();
                b.group(workerGroup);
                b.channel(NioSocketChannel.class);
                b.option(ChannelOption.SO_KEEPALIVE, true);
                b.handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(this);
                    }
                });

                // Start the client.
                ChannelFuture f = b.connect(host, port).sync();

                // Wait until the connection is closed.
                f.channel().closeFuture().sync();
            } finally {
                workerGroup.shutdownGracefully();
            }
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            final ByteBuf time = ctx.alloc().buffer(4);
            time.writeInt((int) (System.currentTimeMillis() / 1000L + 2208988800L));
            System.out.println("channelActive send: " + time);

            final ChannelFuture f = ctx.writeAndFlush(time);
            f.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    ctx.close();
                }
            });
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf m = (ByteBuf) msg;
            try {
                System.out.println("channelRead: " + msg);
                ctx.close();
            } finally {
                m.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

}
