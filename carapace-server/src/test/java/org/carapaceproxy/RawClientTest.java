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
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.carapaceproxy.server.HttpProxyServer;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;

import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.carapaceproxy.utils.RawHttpClient.consumeHttpResponseInput;
import static org.hamcrest.CoreMatchers.containsString;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.client.impl.EndpointConnectionImpl;
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
    public void testConnectionDestroyWhenErrorOnRequest() throws Exception {
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

                TestUtils.waitForCondition(() -> {
                    return conMan.getConnections().getNumActive() == 0 && conMan.getConnections().getNumIdle() == 0;
                }, 100);
            }

            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = stats.getEndpointStats(key);
                System.out.println("stats: " + epstats);
                return epstats.getTotalConnections().intValue() == 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

    //@Test
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

    @Test
    public void testRequestsDebugHeader() throws Exception {

        RawHttpServer httpServer = new RawHttpServer(new HttpServlet() {
            public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                assertThat(request.getHeader(EndpointConnectionImpl.DEBUG_HEADER_NAME), containsString("1"));
                response.getOutputStream().write("it <b>works</b> !!".getBytes());
            }
        });
        int httpServerPort = httpServer.start();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", httpServerPort);
        EndpointKey key = new EndpointKey("localhost", httpServerPort);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            RuntimeServerConfiguration currentConfiguration = server.getCurrentConfiguration();
            currentConfiguration.setRequestsHeaderDebugEnabled(true);
            server.getConnectionsManager().applyNewConfiguration(currentConfiguration);

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                for (int j = 0; j < 2; j++) {
                    RawHttpClient.HttpResponse res = client.get("/index.html");
                    String resp = res.getBodyString();
                    assertTrue(resp.contains("it <b>works</b> !!"));
                }
            } catch (Exception e) {
                System.out.println("EXCEPTION: " + e);
            }
        }
    }

    @Test
    public void testClientsIdleTimeout() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            RuntimeServerConfiguration currentConfiguration = server.getCurrentConfiguration();
            currentConfiguration.setClientsIdleTimeoutSeconds(10);
            server.getConnectionsManager().applyNewConfiguration(currentConfiguration);

            int request = 0;
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                for (request = 0; request < 2; request++) {
                    RawHttpClient.HttpResponse res = client.get("/index.html");
                    if (request == 1) { // second request should be failed due to server connection close.
                        fail();
                    }
                    String resp = res.getBodyString();
                    assertTrue(resp.contains("it <b>works</b> !!"));
                    Thread.sleep(20_000);
                }
            } catch (Exception e) {
                assertThat(request, is(1)); // second request failed due to server connection close.
            }

        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

    @Test
    public void testClientConnectionClose() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withHeader("Connection", "close")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            stats = server.getConnectionsManager().getStats();
            int port = server.getLocalPort();
            assertTrue(port > 0);

            RuntimeServerConfiguration currentConfiguration = server.getCurrentConfiguration();
            server.getConnectionsManager().applyNewConfiguration(currentConfiguration);

            int request = 0;
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                for (request = 0; request < 2; request++) {
                    RawHttpClient.HttpResponse res = client.get("/index.html");
                    if (request == 1) { // second request should be failed due to server connection close.
                        fail();
                    }
                    String resp = res.getBodyString();
                    assertTrue(resp.contains("it <b>works</b> !!"));
                }
            } catch (Exception e) {
                assertThat(request, is(1)); // second request failed due to server connection close.
            }

        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

    @Test
    public void testServerRequestContinue() throws Exception {
        AtomicBoolean responseEnabled = new AtomicBoolean();
        try (DummyServer server = new DummyServer("localhost", 8086, responseEnabled)) {

            TestEndpointMapper mapper = new TestEndpointMapper("localhost", 8086);

            ExecutorService ex = Executors.newFixedThreadPool(2);
            List<Future> futures = new ArrayList<>();

            CarapaceLogger.setLoggingDebugEnabled(true);

            ConnectionsManagerStats stats;
            try (HttpProxyServer proxy = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
                proxy.getCurrentConfiguration().setMaxConnectionsPerEndpoint(1);
                proxy.getCurrentConfiguration().setClientsIdleTimeoutSeconds(300);
                proxy.getCurrentConfiguration().setBorrowTimeout(300_000);
                proxy.getCurrentConfiguration().setRequestsHeaderDebugEnabled(true);
                proxy.getConnectionsManager().applyNewConfiguration(proxy.getCurrentConfiguration());
                proxy.start();
                stats = proxy.getConnectionsManager().getStats();
                int port = proxy.getLocalPort();
                assertTrue(port > 0);

                RuntimeServerConfiguration currentConfiguration = proxy.getCurrentConfiguration();
                proxy.getConnectionsManager().applyNewConfiguration(currentConfiguration);

                AtomicBoolean failed = new AtomicBoolean();
                AtomicBoolean c2go = new AtomicBoolean();
                try {
                    futures.add(ex.submit(() -> {
                        try (RawHttpClient client1 = new RawHttpClient("localhost", port)) {
                            String body = "filler-content";
                            String request = "POST /index.html HTTP/1.1"
                                    + "\r\nHost: localhost"
                                    + "\r\nConnection: keep-alive"
                                    + "\r\nContent-Type: text/plain"
                                    + "\r\n" + HttpHeaderNames.EXPECT + ": " + HttpHeaderValues.CONTINUE
                                    + "\r\nContent-Length: " + body.length()
                                    + "\r\n\r\n";

                            Socket socket = client1.getSocket();
                            OutputStream oo = socket.getOutputStream();

                            oo.write(request.getBytes(StandardCharsets.UTF_8));
                            oo.flush();
                            Thread.sleep(5_000);
                            c2go.set(true);

                            oo.write(body.getBytes(StandardCharsets.UTF_8));
                            oo.flush();

                            String resp = consumeHttpResponseInput(socket.getInputStream()).getBodyString();
                            System.out.println("### RESP client1: " + resp);
                            if (!resp.contains("resp=client1")) {
                                failed.set(true);
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION: " + e);
                            failed.set(true);
                        }
                    }));
                    futures.add(ex.submit(() -> {
                        try {
                            while (!c2go.get()) {
                                Thread.sleep(1_000);
                            }
                            try (RawHttpClient client2 = new RawHttpClient("localhost", port)) {
                                responseEnabled.set(true);
                                RawHttpClient.HttpResponse res = client2.get("/index.html");
                                String resp = res.getBodyString();
                                System.out.println("### RESP client2: " + resp);
                                if (!resp.contains("resp=client2")) {
                                    failed.set(true);
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION: " + e);
                            failed.set(true);
                        }
                    }));
                } finally {
                    for (Future future : futures) {
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            System.out.println("ERR" + e);
                            System.out.println(e);
                        }
                    }
                    ex.shutdown();
                    ex.awaitTermination(1, TimeUnit.MINUTES);
                }
                assertThat(failed.get(), is(false));
            }
            TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
        }
    }

    private static class DummyServer implements AutoCloseable {

        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;
        private final AtomicBoolean responseEnabled;

        public DummyServer(String host, int port, AtomicBoolean responseEnabled) throws InterruptedException {
            this.responseEnabled = responseEnabled == null ? new AtomicBoolean(true) : responseEnabled;
            if (Epoll.isAvailable()) {
                bossGroup = new EpollEventLoopGroup();
                workerGroup = new EpollEventLoopGroup();
            } else { // For windows devs
                bossGroup = new NioEventLoopGroup();
                workerGroup = new NioEventLoopGroup();
            }
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel) throws Exception {
                            channel.pipeline().addLast(new HttpRequestDecoder());
                            channel.pipeline().addLast(new HttpResponseEncoder());
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {

                                private boolean keepAlive;
                                private boolean continueRequest;

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof HttpRequest) {
                                        HttpRequest request = (HttpRequest) msg;
                                        System.out.println("[DummyServer] HttpRequest: " + request);
                                        keepAlive = HttpUtil.isKeepAlive(request);
                                        continueRequest = HttpUtil.is100ContinueExpected(request);
                                        if (continueRequest) {
                                            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                                                    HTTP_1_1, HttpResponseStatus.CONTINUE,
                                                    Unpooled.EMPTY_BUFFER
                                            );
                                            ctx.write(response);
                                        }
                                    } else if (msg instanceof LastHttpContent) {
                                        try {
                                            while (!responseEnabled.get()) {
                                                Thread.sleep(1_000);
                                            }
                                        } catch (Exception ex) {

                                        }

                                        LastHttpContent lastContent = (LastHttpContent) msg;
                                        String trailer = lastContent.content().asReadOnly().readCharSequence(lastContent.content().readableBytes(), Charset.forName("utf-8")).toString();
                                        System.out.println("[DummyServer] LastHttpContent: " + trailer);

                                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                                                HTTP_1_1, HttpResponseStatus.OK,
                                                Unpooled.copiedBuffer("resp=" + (continueRequest ? "client1" : "client2"), Charset.forName("utf-8"))
                                        );
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

                                        if (keepAlive) {
                                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                        }

                                        ctx.write(response);
                                        if (!keepAlive) {
                                            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                                        }
                                    } else if (msg instanceof HttpContent) {
                                        HttpContent content = (HttpContent) msg;
                                        String httpContent = content.content().asReadOnly().readCharSequence(content.content().readableBytes(), Charset.forName("utf-8")).toString();
                                        System.out.println("[DummyServer] HttpContent: " + httpContent);
                                    }
                                }

                                @Override
                                public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
                                    ctx.flush();
                                }

                            });
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            channel = b.bind(host, port).sync().channel();
        }

        @Override
        public void close() throws Exception {
            if (channel != null) {
                channel.close().sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
        }
    }

    @Test
    public void testMultiClientTimeout() throws Exception {
        stubFor(post(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Future> futures = new ArrayList<>();

        CarapaceLogger.setLoggingDebugEnabled(true);

        ConnectionsManagerStats stats;
        try (HttpProxyServer proxy = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            proxy.getCurrentConfiguration().setMaxConnectionsPerEndpoint(1);
            proxy.getCurrentConfiguration().setRequestsHeaderDebugEnabled(true);
            proxy.getCurrentConfiguration().setClientsIdleTimeoutSeconds(10);
            proxy.getConnectionsManager().applyNewConfiguration(proxy.getCurrentConfiguration());
            proxy.start();
            stats = proxy.getConnectionsManager().getStats();
            int port = proxy.getLocalPort();
            assertTrue(port > 0);

            RuntimeServerConfiguration currentConfiguration = proxy.getCurrentConfiguration();
            proxy.getConnectionsManager().applyNewConfiguration(currentConfiguration);

            AtomicBoolean failed = new AtomicBoolean();
            AtomicBoolean c2go = new AtomicBoolean();
            try {
                futures.add(ex.submit(() -> {
                    try (RawHttpClient client1 = new RawHttpClient("localhost", port)) {
                        String body = "filler-content";
                        String request = "POST /index.html HTTP/1.1"
                                + "\r\nHost: localhost"
                                + "\r\nConnection: keep-alive"
                                + "\r\nContent-Type: text/plain"
                                + "\r\n" + HttpHeaderNames.EXPECT + ": " + HttpHeaderValues.CONTINUE
                                + "\r\nContent-Length: " + body.length()
                                + "\r\n\r\n";

                        Socket socket = client1.getSocket();
                        OutputStream oo = socket.getOutputStream();

                        oo.write(request.getBytes(StandardCharsets.UTF_8));
                        oo.flush();
                        Thread.sleep(5_000);
                        c2go.set(true);
                        Thread.sleep(10_000); // should trigger timeout

                        String resp = consumeHttpResponseInput(socket.getInputStream()).getBodyString();
                        System.out.println("### RESP client1: " + resp);
                        if (!resp.isEmpty()) {
                            failed.set(true);
                        }
                    } catch (Throwable e) {
                        System.out.println("EXCEPTION: " + e);
                    }
                }));
                futures.add(ex.submit(() -> {
                    try {
                        while (!c2go.get()) {
                            Thread.sleep(1_000);
                        }
                        try (RawHttpClient client2 = new RawHttpClient("localhost", port)) {
                            RawHttpClient.HttpResponse res = client2.get("/index.html");
                            String resp = res.getBodyString();
                            System.out.println("### RESP client2: " + resp);
                            if (!resp.contains("it <b>works</b> !!")) {
                                failed.set(true);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("EXCEPTION: " + e);
                        failed.set(true);
                    }
                }));
            } finally {
                for (Future future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        System.out.println("ERR" + e);
                    }
                }
                ex.shutdown();
                ex.awaitTermination(1, TimeUnit.MINUTES);
            }
            assertThat(failed.get(), is(false));
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

    @Test
    public void testClientConnectionReuseResetOnConfigurationReload() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")
                        .withHeader("Content-Length", "it <b>works</b> !!".getBytes(StandardCharsets.UTF_8).length + "")
                ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        CarapaceLogger.setLoggingDebugEnabled(true);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                TestUtils.waitForCondition(() -> {
                    EndpointStats epstats = server.getConnectionsManager().getStats().getEndpointStats(key);
                    System.out.println("getTotalConnections: " + epstats.getTotalConnections().intValue());
                    System.out.println("getActiveConnections: " + epstats.getActiveConnections().intValue());
                    System.out.println("getOpenConnections: " + epstats.getOpenConnections().intValue());
                    return epstats.getTotalConnections().intValue() == 1
                            && epstats.getActiveConnections().intValue() == 1
                            && epstats.getOpenConnections().intValue() == 1;
                }, 100);

                // reload configuration gonna reset extisting endpoint-connection > new connection created
                server.getConnectionsManager().applyNewConfiguration(server.getCurrentConfiguration());

                System.out.println("*********************************************************");
                assertEquals("it <b>works</b> !!", client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString());
                TestUtils.waitForCondition(() -> {
                    EndpointStats epstats = server.getConnectionsManager().getStats().getEndpointStats(key);
                    return epstats.getTotalConnections().intValue() == 2
                            && epstats.getActiveConnections().intValue() == 2
                            && epstats.getOpenConnections().intValue() == 2;
                }, 100);
            }
            TestUtils.waitForCondition(() -> {
                EndpointStats epstats = server.getConnectionsManager().getStats().getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 2
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 0;
            }, 100);
            stats = server.getConnectionsManager().getStats();
        }
        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);
    }

}
