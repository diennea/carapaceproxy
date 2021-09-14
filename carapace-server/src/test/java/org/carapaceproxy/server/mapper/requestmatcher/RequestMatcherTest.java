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
package org.carapaceproxy.server.mapper.requestmatcher;

import herddb.utils.TestUtils;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.ws.rs.core.HttpHeaders;
import org.carapaceproxy.core.ClientConnectionHandler;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import org.carapaceproxy.server.mapper.requestmatcher.parser.TokenMgrError;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatcherTest {

    @Test
    public void test() throws Exception {

        Channel ch = mock(Channel.class);
        when(ch.closeFuture()).thenReturn(mock(ChannelFuture.class));
        ChannelHandlerContext chc = mock(ChannelHandlerContext.class);
        when(chc.channel()).thenReturn(ch);

        ClientConnectionHandler cch = mock(ClientConnectionHandler.class);
        when(cch.isSecure()).thenReturn(true);

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/test.html");

        ProxyRequestsManager handler = new ProxyRequestsManager(0, request.uri(), request, null, cch, chc, null, null, null);

        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("all requests", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test.*\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*testio.*\"").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.uri ~ \".*testio.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("secure").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test\\.html\" and secure").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test\\.html\" and secure request", matcher.getDescription());
        }
        {
            // spaces ignored
            RequestMatcher matcher = new RequestMatchParser("request.uri   ~   \".*test.*\" and not secure").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" and not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" or not secure").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" or not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure or request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("not secure request or request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or secure)").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" and (not secure request or secure request)", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or not secure)").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" and (not secure request or not secure request)", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not secure or not secure) and request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("not (not secure request or not secure request) and request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.uri ~\".*test.*\" and (not (not secure or not secure) or (not secure or not secure))"
            ).parse();
            assertTrue(matcher.matches(handler));
            assertEquals(
                    "request.uri ~ \".*test.*\" and (not (not secure request or not secure request) or "
                    + "(not secure request or not secure request))", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) and request.uri ~\".*test.html\"").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) and request.uri ~ \".*test.html\"", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) or not request.uri ~\".*\\.css\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) or not request.uri ~ \".*\\.css\"", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" or request.uri ~\".*\\.html\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*\\.css*\" or request.uri ~ \".*\\.html\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" and request.uri ~\".*\\.html\"").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.uri ~ \".*\\.css*\" and request.uri ~ \".*\\.html\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("not (not request.uri ~ \".*\\.html\")", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not request.uri ~\".*\\.css*\" and not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("not request.uri ~ \".*\\.css*\" and not (not request.uri ~ \".*\\.html\")", matcher.getDescription());
        }

        // property name does not exist -> empty
        {
            RequestMatcher matcher = new RequestMatchParser("request.notex ~\".*test.*\"").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("request.notex ~ \".*test.*\"", matcher.getDescription());
            matcher = new RequestMatchParser("request.notex = \"\"").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.notex = ", matcher.getDescription());
        }
        // Broken one: invalid regexp syntax
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~'.*test.*'").parse();
            matcher.matches(handler);
        });
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri '.*test.*'").parse();
            matcher.matches(handler);
        });

        // Broken ones: all not alone
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri ~\".*test.*\"").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all secure").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all not secure").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("not secure or all").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and all").parse();
        });

        // Fist one condition considered
        {
            RequestMatcher matcher = new RequestMatchParser("not secure all").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure request.uri ~\".*test.*\"").parse();
            assertFalse(matcher.matches(handler));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" all").parse();
            assertTrue(matcher.matches(handler));
            assertEquals("request.uri ~ \".*test.*\"", matcher.getDescription());
        }
    }

    @Test
    public void test2() throws ParseException, ConfigurationNotValidException {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/test.html");
        request.headers().add(HttpHeaders.COOKIE, "test-cookie");
        request.headers().add(HttpHeaders.CONTENT_DISPOSITION, "inline");
        request.headers().add(HttpHeaders.CONTENT_TYPE, "text/html");
        SocketAddress socketAddress = new InetSocketAddress("127.0.0.1", 0);

        Channel ch = mock(Channel.class);
        when(ch.closeFuture()).thenReturn(mock(ChannelFuture.class));
        ChannelHandlerContext chc = mock(ChannelHandlerContext.class);
        when(chc.channel()).thenReturn(ch);

        ClientConnectionHandler cch = mock(ClientConnectionHandler.class);
        when(cch.isSecure()).thenReturn(false);
        when(cch.getListenerHost()).thenReturn("localhost");
        when(cch.getListenerPort()).thenReturn(8080);
        when(cch.getServerAddress()).thenReturn(socketAddress);

        ProxyRequestsManager handler = new ProxyRequestsManager(0, request.uri(), request, null, cch, chc, null, null, null);

        // Test headers
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.COOKIE + " = \"test-cookie\""
                    + " and request.headers." + HttpHeaders.CONTENT_DISPOSITION + " = \"inline\""
                    + " and (not request.headers." + HttpHeaders.USER_AGENT + " = \"chrome\"" // user agent not set
                    + " and not secure)"
            ).parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\"" // user agent not set
            ).parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\""
                    + // user agent not set
                    " and request.headers." + HttpHeaders.ACCEPT + " = \"\"" // not set
            ).parse();
            assertTrue(matcher.matches(handler));
        }
        // Test content-type
        {
            RequestMatcher matcher = new RequestMatchParser("request.content-type = \"text/html\"").parse();
            assertTrue(matcher.matches(handler));
            matcher = new RequestMatchParser("request.content-type = \"application/octet-stream\"").parse();
            assertFalse(matcher.matches(handler));
            matcher = new RequestMatchParser(
                    "not request.content-type ~ \".*test.*\""
                    + " or request.content-type = \"application/octet-stream\""
                    + " or request.content-type ~ \".*html\""
            ).parse();
            assertTrue(matcher.matches(handler));
        }
        // Test method
        {
            RequestMatcher matcher = new RequestMatchParser("request.method = \"GET\"").parse();
            assertTrue(matcher.matches(handler));
            matcher = new RequestMatchParser("request.method = \"POST\"").parse();
            assertFalse(matcher.matches(handler));
            matcher = new RequestMatchParser("not request.method = \"POST\" and request.method = \"GET\"").parse();
            assertTrue(matcher.matches(handler));
        }
        // Test listener.hostport
        {
            RequestMatcher matcher = new RequestMatchParser("listener.hostport = \"localhost:8080\"").parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser("listener.hostport ~ \"localhost:.*\"").parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser("listener.hostport ~ \".*:8080\"").parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser("listener.hostport ~ \"loc.*:80.*\"").parse();
            assertTrue(matcher.matches(handler));

            matcher = new RequestMatchParser("listener.hostport ~ \"some.*:8050\"").parse();
            assertFalse(matcher.matches(handler));
        }
        // Test listener.ipaddress
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.0.1\"").parse();
            assertTrue(matcher.matches(handler));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.1.1\"").parse();
            assertFalse(matcher.matches(handler));
        }
    }
}
