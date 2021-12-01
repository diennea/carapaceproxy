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
package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.io.Files;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.List;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import reactor.netty.http.server.HttpServerRequest;

/**
 *
 * @author francesco.caliumi
 */
public class RequestsLoggerTest {

    private static final boolean DEBUG = true;

    private String accessLogFilePath;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Before
    public void before() {
        accessLogFilePath = tmpDir.getRoot().getAbsolutePath() + "/access.log";
    }

    private RuntimeServerConfiguration genConf() {
        RuntimeServerConfiguration c = new RuntimeServerConfiguration();
        c.setAccessLogPath(accessLogFilePath);
        return c;
    }

    private static List<String> readFile(String path) throws IOException {
        List<String> content = Files.readLines(new File(path), StandardCharsets.UTF_8);
        if (DEBUG) {
            System.out.println("fileContent=\n" + String.join("\n", content));
        }
        return content;
    }

    private static final class MockProxyRequest {

        HttpMethod reqMethod;
        String reqHost;
        String reqUri;
        String remoteIp;
        String localIp;
        String startTs;
        String backendStartTs;
        String endTs;
        MapResult action;
        String userid;
        String sessionid;
    }

    private static ProxyRequest createMockRequestHandler(MockProxyRequest r) throws Exception {

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        HttpHeaders hh = mock(HttpHeaders.class);
        when(hh.getAsString(HttpHeaderNames.HOST)).thenReturn(r.reqHost);

        HttpServerRequest hr = mock(HttpServerRequest.class);
        when(hr.method()).thenReturn(r.reqMethod);
        when(hr.requestHeaders()).thenReturn(hh);

        ProxyRequest rh = mock(ProxyRequest.class);
        when(rh.getRequest()).thenReturn(hr);
        when(rh.getUri()).thenReturn(r.reqUri);
        when(rh.getAction()).thenReturn(r.action);
        when(rh.getRemoteAddress()).thenReturn(new InetSocketAddress(r.remoteIp, 44444));
        when(rh.getLocalAddress()).thenReturn(new InetSocketAddress(r.localIp, 55555));
        when(rh.getStartTs()).thenReturn(f.parse(r.startTs).getTime());
        when(rh.getBackendStartTs()).thenReturn(f.parse(r.backendStartTs).getTime());
        when(rh.getLastActivity()).thenReturn(f.parse(r.endTs).getTime());
        when(rh.getUserId()).thenReturn(r.userid);
        when(rh.getSessionId()).thenReturn(r.sessionid);

        return rh;
    }

    private void run(RequestsLogger reqLogger) throws Exception {
        if (DEBUG) {
            System.out.println("------------------------------------ [");
        }
        long startts = System.currentTimeMillis();
        reqLogger.run();
        if (DEBUG) {
            System.out.println("] ------------------------------------ " + (System.currentTimeMillis() - startts) + "ms");
        }
    }

    @Test
    public void test() throws Exception {

        final int FLUSH_WAIT_TIME = 150;

        RuntimeServerConfiguration c = genConf();
        c.setAccessLogFlushInterval(FLUSH_WAIT_TIME);
        c.setAccessLogMaxQueueCapacity(2); // This could not be hot reloaded

        RequestsLogger reqLogger = new RequestsLogger(c);
        reqLogger.setVerbose(DEBUG);
        reqLogger.setBreakRunForTests(true);

        assertThat((new File(accessLogFilePath)).isFile(), is(false));

        // Wait without a request to log
        {
            long startts = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) >= FLUSH_WAIT_TIME);
        }

        assertThat((new File(accessLogFilePath)).isFile(), is(true));
        assertThat(readFile(accessLogFilePath).size(), is(0));

        {
            // Request to log 1

            MockProxyRequest r1 = new MockProxyRequest();
            r1.reqMethod = HttpMethod.GET;
            r1.reqHost = "thehost";
            r1.reqUri = "/index.html";
            r1.remoteIp = "123.123.123.123";
            r1.localIp = "234.234.234.234";
            r1.startTs = "2018-10-23 10:10:10.000";
            r1.backendStartTs = "2018-10-23 10:10:10.542";
            r1.endTs = "2018-10-23 10:10:11.012";
            r1.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r1.userid = "uid_1";
            r1.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r1));

            long startts = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            reqLogger.flushAccessLogFile();

            List<String> rows1 = readFile(accessLogFilePath);
            assertThat(rows1.size(), is(1));
            assertThat(rows1.get(0), is(
                    "[2018-10-23 10:10:10.000] [GET thehost /index.html] [uid:uid_1, sid:sid_1, ip:123.123.123.123] "
                    + "server=234.234.234.234, act=CACHE, route=routeid_1, backend=host:1111. time t=1012ms b=542ms"));

            // Request to log 2. Let's wait 100ms before do the request and check if flush will be done within the
            // initial FLUSH_WAIT_TIME ms timeframe
            System.out.println("Sleeping 100ms");
            Thread.sleep(100);

            MockProxyRequest r2 = new MockProxyRequest();
            r2.reqMethod = HttpMethod.POST;
            r2.reqHost = "thehost2";
            r2.reqUri = "/index2.html";
            r2.remoteIp = "111.123.123.123";
            r2.localIp = "111.234.234.234";
            r2.startTs = "2018-10-23 11:10:10.000";
            r2.backendStartTs = "2018-10-23 11:10:10.142";
            r2.endTs = "2018-10-23 11:10:10.912";
            r2.action = MapResult.builder()
                    .host("host2")
                    .port(2222)
                    .action(MapResult.Action.PROXY)
                    .routeId("routeid_2")
                    .build();
            r2.userid = "uid_2";
            r2.sessionid = "sid_2";

            reqLogger.logRequest(createMockRequestHandler(r2));

            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            // No flush here. The new line should not have been flushed yet
            //reqLogger.flushAccessLogFile();
            List<String> rows2 = readFile(accessLogFilePath);
            assertThat(rows2.size(), is(1));

            // This run will wait until flush time is over and than flush
            long startts2 = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) >= FLUSH_WAIT_TIME);
            assertTrue((System.currentTimeMillis() - startts2) < FLUSH_WAIT_TIME); // should discount the splept 100ms

            //reqLogger.flushAccessLogFile();
            List<String> rows3 = readFile(accessLogFilePath);
            assertThat(rows3.size(), is(2));
            assertThat(rows3.get(1), is(
                    "[2018-10-23 11:10:10.000] [POST thehost2 /index2.html] [uid:uid_2, sid:sid_2, ip:111.123.123.123] "
                    + "server=111.234.234.234, act=PROXY, route=routeid_2, backend=host2:2222. time t=912ms b=142ms"));
        }

        // Hot configuration reload
        RuntimeServerConfiguration c2 = genConf();
        c2.setAccessLogFlushInterval(FLUSH_WAIT_TIME);
        c2.setAccessLogFormat("[<timestamp>] [<method> <host> <uri>]");
        c2.setAccessLogTimestampFormat("yyyy-MM-dd HH:mm");
        c2.setAccessLogMaxQueueCapacity(2);

        reqLogger.reloadConfiguration(c2);

        {
            // First request will be logged with old format (new conf will be evaluated at next cycle run)

            MockProxyRequest r1 = new MockProxyRequest();
            r1.reqMethod = HttpMethod.GET;
            r1.reqHost = "thehost";
            r1.reqUri = "/index.html";
            r1.remoteIp = "123.123.123.123";
            r1.localIp = "234.234.234.234";
            r1.startTs = "2018-10-23 10:10:10.000";
            r1.backendStartTs = "2018-10-23 10:10:10.542";
            r1.endTs = "2018-10-23 10:10:11.012";
            r1.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r1.userid = "uid_1";
            r1.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r1));

            long startts = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            reqLogger.flushAccessLogFile();

            List<String> rows1 = readFile(accessLogFilePath);
            assertThat(rows1.size(), is(3));
            assertThat(rows1.get(2), is(
                    "[2018-10-23 10:10:10.000] [GET thehost /index.html] [uid:uid_1, sid:sid_1, ip:123.123.123.123] "
                    + "server=234.234.234.234, act=CACHE, route=routeid_1, backend=host:1111. time t=1012ms b=542ms"));

            // This request will be taken in with the new conf
            MockProxyRequest r2 = new MockProxyRequest();
            r2.reqMethod = HttpMethod.GET;
            r2.reqHost = "thehost";
            r2.reqUri = "/index.html";
            r2.remoteIp = "123.123.123.123";
            r2.localIp = "234.234.234.234";
            r2.startTs = "2018-10-23 10:10:10.000";
            r2.backendStartTs = "2018-10-23 10:10:10.542";
            r2.endTs = "2018-10-23 10:10:11.012";
            r2.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r2.userid = "uid_1";
            r2.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r2));

            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            reqLogger.flushAccessLogFile();

            List<String> rows2 = readFile(accessLogFilePath);
            assertThat(rows2.size(), is(4));
            assertThat(rows2.get(3), is(
                    "[2018-10-23 10:10] [GET thehost /index.html]"));
        }

        // Access log writing issues test
        final int WAIT_TIME_BETWEEN_FAILURES = 200;

        RuntimeServerConfiguration c3 = genConf();
        c3.setAccessLogFlushInterval(FLUSH_WAIT_TIME);
        c3.setAccessLogFormat("[<timestamp>] [<method> <host> <uri>]");
        c3.setAccessLogTimestampFormat("yyyy-MM-dd HH:mm");
        c3.setAccessLogPath("notexists/access.log");
        c3.setAccessLogMaxQueueCapacity(2);
        c3.setAccessLogWaitBetweenFailures(WAIT_TIME_BETWEEN_FAILURES);

        reqLogger.reloadConfiguration(c3);

        {
            MockProxyRequest r1 = new MockProxyRequest();
            r1.reqMethod = HttpMethod.GET;
            r1.reqHost = "thehost";
            r1.reqUri = "/index1.html";
            r1.remoteIp = "123.123.123.123";
            r1.localIp = "234.234.234.234";
            r1.startTs = "2018-10-23 11:10:10.000";
            r1.backendStartTs = "2018-10-23 11:10:10.542";
            r1.endTs = "2018-10-23 11:10:11.012";
            r1.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r1.userid = "uid_1";
            r1.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r1));

            // This run will try to create the new file but it will fail and wait WAIT_TIME_BETWEEN_FAILURES
            long startts = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) >= WAIT_TIME_BETWEEN_FAILURES);

            // reqLogger.flushAccessLogFile();
            List<String> rows1 = readFile(accessLogFilePath);
            assertThat(rows1.size(), is(4));

            // Other 2 requests. Only r1 and r2 will be retained (max queue capacity = 2). r3 should be discarded
            MockProxyRequest r2 = new MockProxyRequest();
            r2.reqMethod = HttpMethod.GET;
            r2.reqHost = "thehost";
            r2.reqUri = "/index2.html";
            r2.remoteIp = "123.123.123.123";
            r2.localIp = "234.234.234.234";
            r2.startTs = "2018-10-23 11:10:10.000";
            r2.backendStartTs = "2018-10-23 11:10:10.542";
            r2.endTs = "2018-10-23 11:10:11.012";
            r2.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r2.userid = "uid_1";
            r2.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r2));

            MockProxyRequest r3 = new MockProxyRequest();
            r3.reqMethod = HttpMethod.GET;
            r3.reqHost = "thehost";
            r3.reqUri = "/index3.html";
            r3.remoteIp = "123.123.123.123";
            r3.localIp = "234.234.234.234";
            r3.startTs = "2018-10-23 11:10:10.000";
            r3.backendStartTs = "2018-10-23 11:10:10.542";
            r3.endTs = "2018-10-23 11:10:11.012";
            r3.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r3.userid = "uid_1";
            r3.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r3));

            // Reload conf with previous access log (it should be reopened in append mode)
            RuntimeServerConfiguration c4 = genConf();
            c4.setAccessLogFlushInterval(FLUSH_WAIT_TIME);
            c4.setAccessLogFormat("[<timestamp>] [<method> <host> <uri>]");
            c4.setAccessLogTimestampFormat("yyyy-MM-dd HH:mm");
            c4.setAccessLogPath(accessLogFilePath);
            c4.setAccessLogMaxQueueCapacity(2);
            c4.setAccessLogWaitBetweenFailures(WAIT_TIME_BETWEEN_FAILURES);

            reqLogger.reloadConfiguration(c4);

            long startts2 = System.currentTimeMillis();
//            reqLogger.lastFlush = startts2;
            run(reqLogger); // r1
            run(reqLogger); // r2
            run(reqLogger); // r3 should be missing, it will wait for flush times
            assertTrue((System.currentTimeMillis() - startts2) >= FLUSH_WAIT_TIME - (startts2 - startts)); // needs a little more tolerance

            //reqLogger.flushAccessLogFile();
            List<String> rows2 = readFile(accessLogFilePath);
            assertThat(rows2.size(), is(4 + 2));
            assertThat(rows2.get(4), is(
                    "[2018-10-23 11:10] [GET thehost /index1.html]"));
            assertThat(rows2.get(5), is(
                    "[2018-10-23 11:10] [GET thehost /index2.html]"));
        }

        // Closing RequestLogger
        {
            // r1 will be taken into before close, so it must be writed on access log

            MockProxyRequest r1 = new MockProxyRequest();
            r1.reqMethod = HttpMethod.GET;
            r1.reqHost = "thehost";
            r1.reqUri = "/index1.html";
            r1.remoteIp = "123.123.123.123";
            r1.localIp = "234.234.234.234";
            r1.startTs = "2018-10-23 10:10:10.000";
            r1.backendStartTs = "2018-10-23 10:10:10.542";
            r1.endTs = "2018-10-23 10:10:11.012";
            r1.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r1.userid = "uid_1";
            r1.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r1));

            // Tellig reqLogger we need to close
            reqLogger.close();

            // r2 will be discarded
            MockProxyRequest r2 = new MockProxyRequest();
            r2.reqMethod = HttpMethod.GET;
            r2.reqHost = "thehost";
            r2.reqUri = "/index2.html";
            r2.remoteIp = "123.123.123.123";
            r2.localIp = "234.234.234.234";
            r2.startTs = "2018-10-23 10:10:10.000";
            r2.backendStartTs = "2018-10-23 10:10:10.542";
            r2.endTs = "2018-10-23 10:10:11.012";
            r2.action = MapResult.builder()
                    .host("host")
                    .port(1111)
                    .action(MapResult.Action.CACHE)
                    .routeId("routeid_1")
                    .build();
            r2.userid = "uid_1";
            r2.sessionid = "sid_1";

            reqLogger.logRequest(createMockRequestHandler(r2));

            // First cycle will write r1
            long startts = System.currentTimeMillis();
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            reqLogger.flushAccessLogFile();

            List<String> rows1 = readFile(accessLogFilePath);
            assertThat(rows1.size(), is(6 + 1));
            assertThat(rows1.get(6), is(
                    "[2018-10-23 10:10] [GET thehost /index1.html]"));

            // Second cycle should close the file and return immediatly
            run(reqLogger);
            assertTrue((System.currentTimeMillis() - startts) < FLUSH_WAIT_TIME);

            List<String> rows2 = readFile(accessLogFilePath);
            assertThat(rows2.size(), is(7));
        }

    }

    @Test
    public void testWithServer() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.getCurrentConfiguration().setAccessLogPath(tmpDir.getRoot().getAbsolutePath() + "/access.log");
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                assertTrue(s.endsWith("it <b>works</b> !!"));
                assertFalse(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }

                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    String s = resp.toString();
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                    assertTrue(resp.getHeaderLines().stream().anyMatch(h -> h.contains("X-Cached")));
                }
            }
        }

        readFile(accessLogFilePath);
    }

    @Test
    public void testAccessLogRotation() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port(), true);
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port());

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());) {
            server.getCurrentConfiguration().setAccessLogPath(tmpDir.getRoot().getAbsolutePath() + "/access.log");
            server.getCurrentConfiguration().setAccessLogMaxSize(1024);
            Path currentAccessLogPath = Paths.get(server.getCurrentConfiguration().getAccessLogPath());
            server.start();
            int port = server.getLocalPort();
            System.out.println("CurrentAccessLogPath " + currentAccessLogPath);
            FileChannel logFileChannel = FileChannel.open(currentAccessLogPath);

            while (logFileChannel.size() < 1024) {
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    String s = resp.toString();
                    assertTrue(s.endsWith("it <b>works</b> !!"));
                }

                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    {
                        RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                        String s = resp.toString();
                        assertTrue(s.endsWith("it <b>works</b> !!"));
                    }

                    {
                        RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                        String s = resp.toString();
                        assertTrue(s.endsWith("it <b>works</b> !!"));
                    }
                }
            }
            Thread.sleep(3000);
            //check if gzip file exist
            File[] f = new File(tmpDir.getRoot().getAbsolutePath()).listFiles((dir, name) -> name.startsWith("access") && name.endsWith(".gzip"));
            assertTrue(f.length == 1);
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                assertTrue(s.endsWith("it <b>works</b> !!"));
            }
            assertTrue(logFileChannel.size() > 0);
        }
    }

}
