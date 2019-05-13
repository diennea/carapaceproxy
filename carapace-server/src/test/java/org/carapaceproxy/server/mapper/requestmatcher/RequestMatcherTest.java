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
import javax.ws.rs.core.HttpHeaders;
import org.carapaceproxy.server.ClientConnectionHandler;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.RequestMatchingContext;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import org.carapaceproxy.server.mapper.requestmatcher.parser.TokenMgrError;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatcherTest {

    @Test
    public void test() throws Exception {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.getUri()).thenReturn("test.html");

        ClientConnectionHandler cch = mock(ClientConnectionHandler.class);
        when(cch.isSecure()).thenReturn(true);
        when(handler.getClientConnectionHandler()).thenReturn(cch);

        MatchingContext context = new RequestMatchingContext(handler);
        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test.*\"").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*testio.*\"").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("https").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not https").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test\\.html\" and https").parse();
            assertTrue(matcher.matches(context));
        }
        {
            // spaces ignored
            RequestMatcher matcher = new RequestMatchParser("request.uri   ~   \".*test.*\" and not https").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" or not https").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not https or request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not https or https)").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not https or not https)").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not https or not https) and request.uri ~\".*test.*\"").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not https or not https) or (not https or not https))").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not https or not https) and (not https or not https)) and request.uri ~\".*test.html\"").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not https or not https) and (not https or not https)) or not request.uri ~\".*\\.css\"").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" or request.uri ~\".*\\.html\"").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" and request.uri ~\".*\\.html\"").parse();
            assertFalse(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(context));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not request.uri ~\".*\\.css*\" and not (not request.uri ~\".*\\.html\")").parse();
            assertTrue(matcher.matches(context));
        }

        // Broken one: property name does not exist
        TestUtils.assertThrows(MatchingException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.notex ~\".*test.*\"").parse();
            matcher.matches(context);
        });
        // Broken one: invalid regexp syntax
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~'.*test.*'").parse();
            matcher.matches(context);
        });
        TestUtils.assertThrows(TokenMgrError.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri '.*test.*'").parse();
            matcher.matches(context);
        });

        // Broken ones: all not alone
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri ~\".*test.*\"").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all https").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all not https").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("not https or all").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and all").parse();
        });

        // Fist one condition considered
        assertFalse(new RequestMatchParser("not https all").parse().matches(context));
        assertFalse(new RequestMatchParser("not https request.uri ~\".*test.*\"").parse().matches(context));
        assertTrue(new RequestMatchParser("request.uri ~\".*test.*\" all").parse().matches(context));
    }

    @Test
    public void test2() throws MatchingException, ParseException, ConfigurationNotValidException {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.getUri()).thenReturn("t/est.html");


        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/test.html");
        request.headers().add(HttpHeaders.COOKIE, "test-cookie");
        request.headers().add(HttpHeaders.CONTENT_DISPOSITION, "inline");
        request.headers().add(HttpHeaders.CONTENT_TYPE, "text/html");
        when(handler.getRequest()).thenReturn(request);

        ClientConnectionHandler cch = mock(ClientConnectionHandler.class);
        when(cch.isSecure()).thenReturn(false);
        when(handler.getClientConnectionHandler()).thenReturn(cch);

        when(cch.getListenerHost()).thenReturn("localhost");
        when(cch.getListenerPort()).thenReturn(8080);

        MatchingContext context = new RequestMatchingContext(handler);
        // Test headers
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.COOKIE + " = \"test-cookie\""
                    + " and request.headers." + HttpHeaders.CONTENT_DISPOSITION + " = \"inline\""
                    + " and (not request.headers." + HttpHeaders.USER_AGENT + " = \"chrome\"" // user agent not set
                    + " and not https)"
            ).parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\"" // user agent not set
            ).parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\""
                    + // user agent not set
                    " and request.headers." + HttpHeaders.ACCEPT + " = \"\"" // not set
            ).parse();
            assertTrue(matcher.matches(context));
        }
        // Test content-type
        {
            RequestMatcher matcher = new RequestMatchParser("request.content-type = \"text/html\"").parse();
            assertTrue(matcher.matches(context));
            matcher = new RequestMatchParser("request.content-type = \"application/octet-stream\"").parse();
            assertFalse(matcher.matches(context));
            matcher = new RequestMatchParser(
                    "not request.content-type ~ \".*test.*\""
                    + " or request.content-type = \"application/octet-stream\""
                    + " or request.content-type ~ \".*html\""
            ).parse();
            assertTrue(matcher.matches(context));
        }
        // Test method
        {
            RequestMatcher matcher = new RequestMatchParser("request.method = \"GET\"").parse();
            assertTrue(matcher.matches(context));
            matcher = new RequestMatchParser("request.method = \"POST\"").parse();
            assertFalse(matcher.matches(context));
            matcher = new RequestMatchParser("not request.method = \"POST\" and request.method = \"GET\"").parse();
            assertTrue(matcher.matches(context));
        }
        // Test listener.address
        {
            RequestMatcher matcher = new RequestMatchParser("listener.address = \"localhost:8080\"").parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser("listener.address ~ \"localhost:.*\"").parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser("listener.address ~ \".*:8080\"").parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser("listener.address ~ \"loc.*:80.*\"").parse();
            assertTrue(matcher.matches(context));

            matcher = new RequestMatchParser("listener.address ~ \"some.*:8050\"").parse();
            assertFalse(matcher.matches(context));
        }
    }
}
