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
package org.carapaceproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.carapaceproxy.utils.RawHttpClient.consumeHttpResponseInput;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static reactor.netty.http.HttpProtocol.HTTP11;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.carapaceproxy.utils.RawHttpServer;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 *
 * @author enrico.olivelli
 */
@RunWith(JUnitParamsRunner.class)
public class RawClientTest {

    private static final Logger LOG = Logger.getLogger(RawClientTest.class.getName());

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public TestName testName = new TestName();

    @Before
    public void dumpTestName() {
        LOG.log(Level.INFO, "Starting {0}", testName.getMethodName());
    }

    @After
    public void dumpTestNameEnd() {
        LOG.log(Level.INFO, "End {0}", testName.getMethodName());
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
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
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
        }
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

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {

                for (int i = 0; i < 1000; i++) {
                    String s = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                    assertEquals("a", s);
                }

            }
        }
    }

    @Test
    public void endpointKeyTest() {
        {
            EndpointKey entryPoint = EndpointKey.make("localhost:8080");
            assertThat(entryPoint.host(), is("localhost"));
            assertThat(entryPoint.port(), is(8080));
        }
        {
            EndpointKey entryPoint = EndpointKey.make("localhost");
            assertThat(entryPoint.host(), is("localhost"));
            assertThat(entryPoint.port(), is(0));
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
        }
    }

    @Test
    public void testKeepAliveTimeout() throws Exception {
        RawHttpServer httpServer = new RawHttpServer(new HttpServlet() {
            public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/html");
                PrintWriter out = response.getWriter();
                out.println("it <b>works</b> !!");
            }
        });
        httpServer.setIdleTimeout(5);
        int httpServerPort = httpServer.start();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", httpServerPort);
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
            public void doGet(HttpServletRequest request, HttpServletResponse response) {
            }
        });
        int httpServerPort = httpServer.start();

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", httpServerPort);
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
    public void testServerRequestContinue() throws Exception {
        AtomicBoolean responseEnabled = new AtomicBoolean();
        try (DummyServer server = new DummyServer("localhost", 8086, responseEnabled)) {

            TestEndpointMapper mapper = new TestEndpointMapper("localhost", 8086);

            ExecutorService ex = Executors.newFixedThreadPool(2);
            List<Future> futures = new ArrayList<>();

            CarapaceLogger.setLoggingDebugEnabled(true);

            try (HttpProxyServer proxy = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
                ConnectionPoolConfiguration defaultConnectionPool = proxy.getCurrentConfiguration().getDefaultConnectionPool();
                defaultConnectionPool.setMaxConnectionsPerEndpoint(1);
                proxy.getCurrentConfiguration().setClientsIdleTimeoutSeconds(300);
                proxy.getProxyRequestsManager().reloadConfiguration(proxy.getCurrentConfiguration(), mapper.getBackends().values());
                proxy.start();
                int port = proxy.getLocalPort();
                assertTrue(port > 0);

                AtomicBoolean failed = new AtomicBoolean();
                AtomicBoolean c2go = new AtomicBoolean();
                try {
                    futures.add(ex.submit(() -> {
                        try (RawHttpClient client1 = new RawHttpClient("localhost", port)) {
                            String body = "filler-content";
                            String request = "POST /index.html HTTP/1.1"
                                    + "\r\n" + HttpHeaderNames.HOST + ": localhost"
                                    + "\r\n" + HttpHeaderNames.CONNECTION + ": " + HttpHeaderValues.KEEP_ALIVE
                                    + "\r\n" + HttpHeaderNames.CONTENT_TYPE + ": " + HttpHeaderValues.TEXT_PLAIN
                                    + "\r\n" + HttpHeaderNames.EXPECT + ": " + HttpHeaderValues.CONTINUE
                                    + "\r\n" + HttpHeaderNames.CONTENT_LENGTH + ": " + body.length()
                                    + "\r\n\r\n";

                            Socket socket = client1.getSocket();
                            OutputStream oo = socket.getOutputStream();

                            oo.write(request.getBytes(StandardCharsets.UTF_8));
                            oo.flush();
                            Thread.sleep(5_000);
                            c2go.set(true);

                            oo.write(body.getBytes(StandardCharsets.UTF_8));
                            oo.flush();

                            String resp = consumeHttpResponseInput(socket.getInputStream()).getStatusLine();
                            System.out.println("### RESP client1: " + resp);
                            if (!resp.contains("HTTP/1.1 100 Continue")) {
                                failed.set(true);
                                return;
                            }
                            int count = 0;
                            do {
                                Thread.sleep(5_000);
                                resp = consumeHttpResponseInput(socket.getInputStream()).getBodyString();
                                System.out.println("### RESP client1: " + resp);
                            } while (!resp.contains("resp=client1") && count++ < 10);
                            if (count >= 10) {
                                failed.set(true);
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION client1: " + e);
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
                            System.out.println("EXCEPTION client2: " + e);
                            failed.set(true);
                        }
                    }));
                } finally {
                    for (Future future : futures) {
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            System.out.println("ERR" + e);
                            failed.set(true);
                        }
                    }
                    ex.shutdown();
                    ex.awaitTermination(1, TimeUnit.MINUTES);
                }
                assertThat(failed.get(), is(false));
            }
        }
    }

    private static class DummyServer implements AutoCloseable {

        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;

        public DummyServer(String host, int port, AtomicBoolean responseEnabled) throws InterruptedException {
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
                        public void initChannel(SocketChannel channel) {
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
                                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                        if (keepAlive) {
                                            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                                        }
                                        ctx.writeAndFlush(response);
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
                                public void channelReadComplete(ChannelHandlerContext ctx) {
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

        try (HttpProxyServer proxy = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            ConnectionPoolConfiguration defaultConnectionPool = proxy.getCurrentConfiguration().getDefaultConnectionPool();
            defaultConnectionPool.setMaxConnectionsPerEndpoint(1);
            proxy.getCurrentConfiguration().setClientsIdleTimeoutSeconds(10);
            proxy.getProxyRequestsManager().reloadConfiguration(proxy.getCurrentConfiguration(), mapper.getBackends().values());
            proxy.start();
            int port = proxy.getLocalPort();
            assertTrue(port > 0);

            AtomicBoolean failed = new AtomicBoolean();
            AtomicBoolean c2go = new AtomicBoolean();
            try {
                futures.add(ex.submit(() -> {
                    try (RawHttpClient client1 = new RawHttpClient("localhost", port, 300_000)) {
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
                        failed.set(true);
                    }
                }));
                futures.add(ex.submit(() -> {
                    try {
                        while (!c2go.get()) {
                            Thread.sleep(1_000);
                        }
                        try (RawHttpClient client2 = new RawHttpClient("localhost", port, 300_000)) {
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
                        failed.set(true);
                    }
                }
                ex.shutdown();
                ex.awaitTermination(1, TimeUnit.MINUTES);
            }
            assertThat(failed.get(), is(false));
        }
    }

    @Test
    public void testMaxConnectionsAndBorrowTimeout() throws Exception {
        CarapaceLogger.setLoggingDebugEnabled(true);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Future> futures = new ArrayList<>();
        AtomicBoolean responseEnabled = new AtomicBoolean();

        try (DummyServer server = new DummyServer("localhost", 8086, responseEnabled)) {
            TestEndpointMapper mapper = new TestEndpointMapper("localhost", 8086, false);
            try (HttpProxyServer proxy = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
                ConnectionPoolConfiguration defaultConnectionPool = proxy.getCurrentConfiguration().getDefaultConnectionPool();
                defaultConnectionPool.setMaxConnectionsPerEndpoint(1);
                defaultConnectionPool.setBorrowTimeout(1);
                proxy.getProxyRequestsManager().reloadConfiguration(proxy.getCurrentConfiguration(), mapper.getBackends().values());
                proxy.start();
                int port = proxy.getLocalPort();
                assertTrue(port > 0);

                AtomicBoolean failed = new AtomicBoolean();
                AtomicBoolean c2go = new AtomicBoolean();
                try {
                    futures.add(ex.submit(() -> {
                        try (RawHttpClient client1 = new RawHttpClient("localhost", port, 300_000)) {
                            String body = "filler-content";
                            String request = "POST /index.html HTTP/1.1"
                                    + "\r\n" + HttpHeaderNames.HOST + ": localhost"
                                    + "\r\n" + HttpHeaderNames.CONNECTION + ": " + HttpHeaderValues.KEEP_ALIVE
                                    + "\r\n" + HttpHeaderNames.CONTENT_TYPE + ": " + HttpHeaderValues.TEXT_PLAIN
                                    + "\r\n" + HttpHeaderNames.EXPECT + ": " + HttpHeaderValues.CONTINUE
                                    + "\r\n" + HttpHeaderNames.CONTENT_LENGTH + ": " + body.length()
                                    + "\r\n\r\n";

                            Socket socket = client1.getSocket();
                            OutputStream oo = socket.getOutputStream();

                            oo.write(request.getBytes(StandardCharsets.UTF_8));
                            oo.flush();
                            Thread.sleep(5_000);
                            c2go.set(true);
                            Thread.sleep(15_000); // throws client2 borrow timeout (no connections available)

                            oo.write(body.getBytes(StandardCharsets.UTF_8));
                            oo.flush();

                            String resp = consumeHttpResponseInput(socket.getInputStream()).getStatusLine();
                            System.out.println("### RESP client1: " + resp);
                            if (!resp.contains("HTTP/1.1 100 Continue")) {
                                failed.set(true);
                                return;
                            }
                            int count = 0;
                            do {
                                Thread.sleep(5_000);
                                resp = consumeHttpResponseInput(socket.getInputStream()).getBodyString();
                                System.out.println("### RESP client1: " + resp);
                            } while (!resp.contains("resp=client1") && count++ < 10);
                            if (count >= 10) {
                                failed.set(true);
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION client1: " + e);
                            failed.set(true);
                        }
                    }));
                    futures.add(ex.submit(() -> {
                        try {
                            while (!c2go.get()) {
                                Thread.sleep(1_000);
                            }
                            try (RawHttpClient client2 = new RawHttpClient("localhost", port, 300_000)) {
                                String resp = client2.get("/index.html").getBodyString();
                                System.out.println("### RESP client2: " + resp);
                                if (!resp.contains("Service Unavailable")) { // borrow timeout
                                    failed.set(true);
                                    return;
                                }
                                responseEnabled.set(true);
                            }
                        } catch (Exception e) {
                            System.out.println("EXCEPTION client2: " + e);
                            failed.set(true);
                        }
                    }));
                } finally {
                    for (Future future : futures) {
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            System.out.println("ERR" + e);
                            failed.set(true);
                        }
                    }
                    ex.shutdown();
                    ex.awaitTermination(1, TimeUnit.MINUTES);
                }
                assertThat(failed.get(), is(false));
            }
        }
    }

    @Test
    public void testInvalidUriChars() throws Exception {
        stubFor(get(UrlPattern.ANY)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String s = client.executeRequest("GET /index[1].html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBodyString();
                System.out.println("s:" + s);
                assertEquals("it <b>works</b> !!", s);
            }
        }
    }

    @Test
    @Parameters({"http", "https"})
    public void testClosedProxy(String scheme) throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        // Proxy requests have to use "localhost:port" as endpoint instead of the one in the url (ex yahoo.com)
        // in order to avoid open proxy vulnerability
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());
        try (HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot());) {
            server.addCertificate(new SSLCertificateConfiguration("localhost", null, "localhost.p12", "testproxy", STATIC));
            server.addListener(new NetworkListenerConfiguration("localhost", 0, scheme.equals("https"), null, "localhost", DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 100, DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HTTP11.name())));

            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port, scheme.equals("https"))) {

                stubFor(get("/index.html?p1=v1&p2=https://localhost/index.html?p=1")
                        .withQueryParams(Map.of("p1", equalTo("v1"), "p2", equalTo("https://localhost/index.html?p=1")))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));
                String s = client.executeRequest("GET " + scheme + "://yahoo.com/index.html?p1=v1&p2=https://localhost/index.html?p=1 HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.get("/index.html?p1=v1&p2=https://localhost/index.html?p=1").getBodyString();
                assertEquals("it <b>works</b> !!", s);

                stubFor(get("/index.html")
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));
                s = client.executeRequest("GET " + scheme + "://yahoo.com/index.html HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.get("/index.html").getBodyString();
                assertEquals("it <b>works</b> !!", s);

                stubFor(get("/?p1=v1&p2=https://localhost/index.html?p=1")
                        .withQueryParams(Map.of("p1", equalTo("v1"), "p2", equalTo("https://localhost/index.html?p=1")))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));
                s = client.executeRequest("GET " + scheme + "://yahoo.com/?p1=v1&p2=https://localhost/index.html?p=1 HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.get("/?p1=v1&p2=https://localhost/index.html?p=1").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.executeRequest("GET " + scheme + "://yahoo.com?p1=v1&p2=https://localhost/index.html?p=1 HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.get("?p1=v1&p2=https://localhost/index.html?p=1").getBodyString();
                assertEquals("it <b>works</b> !!", s);

                stubFor(get("/")
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                                .withBody("it <b>works</b> !!")));
                s = client.executeRequest("GET " + scheme + "://yahoo.com/ HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.get("/").getBodyString();
                assertEquals("it <b>works</b> !!", s);
                s = client.executeRequest("GET " + scheme + "://yahoo.com HTTP/1.1\r\nHost: localhost\r\n\r\n").getBodyString();
                assertEquals("it <b>works</b> !!", s);
            }
        }
    }

    @Test
    public void testCookies() throws Exception {
        stubFor(get("/index.html")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaderNames.SET_COOKIE.toString(), "responseCookie=responseValue; responseCookie2=responseValue2")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                HttpResponse resp = client.executeRequest(
                        "GET /index.html HTTP/1.1"
                        + "\r\nHost: localhost"
                        + "\r\n" + HttpHeaderNames.COOKIE + ": requestCookie=requestValue; requestCookie2=requestValue2"
                        + "\r\nConnection: close\r\n\r\n"
                );
                assertEquals("it <b>works</b> !!", resp.getBodyString());

                // cookies from server
                List<String> headerSetCookie = resp.getHeaderLines().stream()
                        .filter(h -> h.toLowerCase().contains("set-cookie"))
                        .toList();
                assertThat(headerSetCookie.size(), is(1));
                assertThat(headerSetCookie.get(0), is("Set-Cookie: responseCookie=responseValue; responseCookie2=responseValue2\r\n"));
            }
        }

        // cookies from client
        HttpHeader headerCookie = findAll(getRequestedFor(urlMatching("/index.html")))
                .get(0)
                .getHeaders()
                .getHeader(HttpHeaderNames.COOKIE.toString());
        assertThat(headerCookie.values().size(), is(1));
        assertThat(headerCookie.values().get(0), is("requestCookie=requestValue; requestCookie2=requestValue2"));
    }
}
