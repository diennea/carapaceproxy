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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import herddb.utils.TestUtils;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import java.net.InetSocketAddress;
import javax.ws.rs.core.HttpHeaders;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import org.carapaceproxy.server.mapper.requestmatcher.parser.TokenMgrError;
import org.junit.Test;
import reactor.netty.http.server.HttpServerRequest;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatcherTest {

    @Test
    public void test() throws Exception {
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.uri()).thenReturn("/test.html");
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        when(serverRequest.scheme()).thenReturn("https");
        when(serverRequest.protocol()).thenReturn("HTTP/2");

        ProxyRequest request = new ProxyRequest(serverRequest, null, null);

        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertTrue(matcher.matches(request));
            assertEquals("all requests", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test.*\"").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*testio.*\"").parse();
            assertFalse(matcher.matches(request));
            assertEquals("request.uri ~ \".*testio.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("secure").parse();
            assertTrue(matcher.matches(request));
            assertEquals("secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure").parse();
            assertFalse(matcher.matches(request));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test\\.html\" and secure").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test\\.html\" and secure request", matcher.getDescription());
        }
        {
            // spaces ignored
            RequestMatcher matcher = new RequestMatchParser("request.uri   ~   \".*test.*\" and not secure").parse();
            assertFalse(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" and not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" or not secure").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" or not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure or request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(request));
            assertEquals("not secure request or request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or secure)").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" and (not secure request or secure request)", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or not secure)").parse();
            assertFalse(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" and (not secure request or not secure request)", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not secure or not secure) and request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(request));
            assertEquals("not (not secure request or not secure request) and request.uri ~ \".*test.*\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.uri ~\".*test.*\" and (not (not secure or not secure) or (not secure or not secure))"
            ).parse();
            assertTrue(matcher.matches(request));
            assertEquals(
                    "request.uri ~ \".*test.*\" and (not (not secure request or not secure request) or "
                    + "(not secure request or not secure request))", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) and request.uri ~\".*test.html\"").parse();
            assertFalse(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) and request.uri ~ \".*test.html\"", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) or not request.uri ~\".*\\.css\"").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) or not request.uri ~ \".*\\.css\"", matcher.getDescription()
            );
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" or request.uri ~\".*\\.html\"").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*\\.css*\" or request.uri ~ \".*\\.html\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" and request.uri ~\".*\\.html\"").parse();
            assertFalse(matcher.matches(request));
            assertEquals("request.uri ~ \".*\\.css*\" and request.uri ~ \".*\\.html\"", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(request));
            assertEquals("not (not request.uri ~ \".*\\.html\")", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not request.uri ~\".*\\.css*\" and not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(request));
            assertEquals("not request.uri ~ \".*\\.css*\" and not (not request.uri ~ \".*\\.html\")", matcher.getDescription());
        }

        // property name does not exist -> error
        {
            RequestMatcher matcher = new RequestMatchParser("request.notex ~\".*test.*\"").parse();
            assertEquals("request.notex ~ \".*test.*\"", matcher.getDescription());
            TestUtils.assertThrows(IllegalArgumentException.class, () -> matcher.matches(request));
        }
        // Broken one: invalid regexp syntax
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~'.*test.*'").parse();
            matcher.matches(request);
        });
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri '.*test.*'").parse();
            matcher.matches(request);
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
            assertFalse(matcher.matches(request));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure request.uri ~\".*test.*\"").parse();
            assertFalse(matcher.matches(request));
            assertEquals("not secure request", matcher.getDescription());
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" all").parse();
            assertTrue(matcher.matches(request));
            assertEquals("request.uri ~ \".*test.*\"", matcher.getDescription());
        }
    }

    @Test
    public void test2() throws ParseException, ConfigurationNotValidException {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.add(HttpHeaders.COOKIE, "test-cookie");
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline");
        headers.add(HttpHeaders.CONTENT_TYPE, "text/html");

        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.uri()).thenReturn("/test.html");
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        when(serverRequest.scheme()).thenReturn("http");
        when(serverRequest.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));
        when(serverRequest.hostAddress()).thenReturn(new InetSocketAddress("127.0.0.2", 0));
        when(serverRequest.requestHeaders()).thenReturn(headers);
        when(serverRequest.protocol()).thenReturn("HTTP/2");

        ProxyRequest request = new ProxyRequest(serverRequest, null, new EndpointKey("localhost", 8080));

        // Test headers
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.COOKIE + " = \"test-cookie\""
                    + " and request.headers." + HttpHeaders.CONTENT_DISPOSITION + " = \"inline\""
                    + " and (not request.headers." + HttpHeaders.USER_AGENT + " = \"chrome\"" // user agent not set
                    + " and not secure)"
            ).parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\"" // user agent not set
            ).parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\""
                    + // user agent not set
                    " and request.headers." + HttpHeaders.ACCEPT + " = \"\"" // not set
            ).parse();
            assertTrue(matcher.matches(request));
        }
        // Test content-type
        {
            RequestMatcher matcher = new RequestMatchParser("request.content-type = \"text/html\"").parse();
            assertTrue(matcher.matches(request));
            matcher = new RequestMatchParser("request.content-type = \"application/octet-stream\"").parse();
            assertFalse(matcher.matches(request));
            matcher = new RequestMatchParser(
                    "not request.content-type ~ \".*test.*\""
                    + " or request.content-type = \"application/octet-stream\""
                    + " or request.content-type ~ \".*html\""
            ).parse();
            assertTrue(matcher.matches(request));
        }
        // Test method
        {
            RequestMatcher matcher = new RequestMatchParser("request.method = \"GET\"").parse();
            assertTrue(matcher.matches(request));
            matcher = new RequestMatchParser("request.method = \"POST\"").parse();
            assertFalse(matcher.matches(request));
            matcher = new RequestMatchParser("not request.method = \"POST\" and request.method = \"GET\"").parse();
            assertTrue(matcher.matches(request));
        }
        // Test listener.hostport
        {
            RequestMatcher matcher = new RequestMatchParser("listener.hostport = \"localhost:8080\"").parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser("listener.hostport ~ \"localhost:.*\"").parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser("listener.hostport ~ \".*:8080\"").parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser("listener.hostport ~ \"loc.*:80.*\"").parse();
            assertTrue(matcher.matches(request));

            matcher = new RequestMatchParser("listener.hostport ~ \"some.*:8050\"").parse();
            assertFalse(matcher.matches(request));
        }
        // Test listener.ipaddress
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.0.2\"").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.1.2\"").parse();
            assertFalse(matcher.matches(request));
        }
    }
}
