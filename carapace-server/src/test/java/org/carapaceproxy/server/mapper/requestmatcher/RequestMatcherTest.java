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

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import herddb.utils.TestUtils;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import java.net.InetSocketAddress;
import javax.ws.rs.core.HttpHeaders;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import org.carapaceproxy.server.mapper.requestmatcher.parser.TokenMgrError;
import org.junit.jupiter.api.Test;
import reactor.netty.http.server.HttpServerRequest;

/**
 *
 * @author paolo.venturi
 */
class RequestMatcherTest {

    @Test
    void test() throws Exception {
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.uri()).thenReturn("/test.html");
        when(serverRequest.fullPath()).thenReturn("/test.html");
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        when(serverRequest.scheme()).thenReturn("https");
        when(serverRequest.protocol()).thenReturn("HTTP/2");

        ProxyRequest request = new ProxyRequest(serverRequest, null, null);

        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("all requests");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test.*\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*testio.*\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*testio.*\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("secure").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("not secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~ \".*test\\.html\" and secure").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test\\.html\" and secure request");
        }
        {
            // spaces ignored
            RequestMatcher matcher = new RequestMatchParser("request.uri   ~   \".*test.*\" and not secure").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and not secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" or not secure").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" or not secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure or request.uri ~\".*test.*\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("not secure request or request.uri ~ \".*test.*\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or secure)").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and (not secure request or secure request)");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not secure or not secure)").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and (not secure request or not secure request)");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not secure or not secure) and request.uri ~\".*test.*\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("not (not secure request or not secure request) and request.uri ~ \".*test.*\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser(
                    "request.uri ~\".*test.*\" and (not (not secure or not secure) or (not secure or not secure))"
            ).parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) or "
                    + "(not secure request or not secure request))");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) and request.uri ~\".*test.html\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) and request.uri ~ \".*test.html\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and (not (not secure or not secure) "
                    + "and (not secure or not secure)) or not request.uri ~\".*\\.css\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\" and (not (not secure request or not secure request) "
                    + "and (not secure request or not secure request)) or not request.uri ~ \".*\\.css\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" or request.uri ~\".*\\.html\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*\\.css*\" or request.uri ~ \".*\\.html\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*\\.css*\" and request.uri ~\".*\\.html\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*\\.css*\" and request.uri ~ \".*\\.html\"");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not (not request.uri ~\".*\\.html\")").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("not (not request.uri ~ \".*\\.html\")");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not request.uri ~\".*\\.css*\" and not (not request.uri ~\".*\\.html\")").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("not request.uri ~ \".*\\.css*\" and not (not request.uri ~ \".*\\.html\")");
        }

        // property name does not exist -> error
        {
            RequestMatcher matcher = new RequestMatchParser("request.notex ~\".*test.*\"").parse();
            assertThat(matcher.getDescription()).isEqualTo("request.notex ~ \".*test.*\"");
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> matcher.matches(request));
        }
        // Broken one: invalid regexp syntax
        assertThatExceptionOfType(TokenMgrError.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~'.*test.*'").parse();
            matcher.matches(request);
        });
        assertThatExceptionOfType(TokenMgrError.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri '.*test.*'").parse();
            matcher.matches(request);
        });

        // Broken ones: all not alone
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri ~\".*test.*\"").parse();
        });
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("all request.uri").parse();
        });
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("all secure").parse();
        });
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("all not secure").parse();
        });
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("not secure or all").parse();
        });
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" and all").parse();
        });

        // Fist one condition considered
        {
            RequestMatcher matcher = new RequestMatchParser("not secure all").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("not secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not secure request.uri ~\".*test.*\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            assertThat(matcher.getDescription()).isEqualTo("not secure request");
        }
        {
            RequestMatcher matcher = new RequestMatchParser("request.uri ~\".*test.*\" all").parse();
            assertThat(matcher.matches(request)).isTrue();
            assertThat(matcher.getDescription()).isEqualTo("request.uri ~ \".*test.*\"");
        }
    }

    @Test
    void test2() throws Exception {
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
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\"" // user agent not set
            ).parse();
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser(
                    "request.headers." + HttpHeaders.USER_AGENT + " = \"\""
                    + // user agent not set
                    " and request.headers." + HttpHeaders.ACCEPT + " = \"\"" // not set
            ).parse();
            assertThat(matcher.matches(request)).isTrue();
        }
        // Test content-type
        {
            RequestMatcher matcher = new RequestMatchParser("request.content-type = \"text/html\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            matcher = new RequestMatchParser("request.content-type = \"application/octet-stream\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            matcher = new RequestMatchParser(
                    "not request.content-type ~ \".*test.*\""
                    + " or request.content-type = \"application/octet-stream\""
                    + " or request.content-type ~ \".*html\""
            ).parse();
            assertThat(matcher.matches(request)).isTrue();
        }
        // Test method
        {
            RequestMatcher matcher = new RequestMatchParser("request.method = \"GET\"").parse();
            assertThat(matcher.matches(request)).isTrue();
            matcher = new RequestMatchParser("request.method = \"POST\"").parse();
            assertThat(matcher.matches(request)).isFalse();
            matcher = new RequestMatchParser("not request.method = \"POST\" and request.method = \"GET\"").parse();
            assertThat(matcher.matches(request)).isTrue();
        }
        // Test listener.hostport
        {
            RequestMatcher matcher = new RequestMatchParser("listener.hostport = \"localhost:8080\"").parse();
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser("listener.hostport ~ \"localhost:.*\"").parse();
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser("listener.hostport ~ \".*:8080\"").parse();
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser("listener.hostport ~ \"loc.*:80.*\"").parse();
            assertThat(matcher.matches(request)).isTrue();

            matcher = new RequestMatchParser("listener.hostport ~ \"some.*:8050\"").parse();
            assertThat(matcher.matches(request)).isFalse();
        }
        // Test listener.ipaddress
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.0.2\"").parse();
            assertThat(matcher.matches(request)).isTrue();
        }
        {
            RequestMatcher matcher = new RequestMatchParser("listener.ipaddress = \"127.0.1.2\"").parse();
            assertThat(matcher.matches(request)).isFalse();
        }
    }

    @Test
    void absoluteFormRequestTargetMatchesPathRule() throws Exception {
        // An absolute-form request target (scheme://authority/path) is valid HTTP/1.1 and
        // is forwarded to the backend as just "/path" (see ProxyRequest.getUri()). A
        // path-anchored route/ACL must therefore still match on "/path"; otherwise it can
        // be bypassed by sending the same request in absolute form.
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.uri()).thenReturn("http://victim.example/admin/secret");
        when(serverRequest.fullPath()).thenReturn("/admin/secret");
        when(serverRequest.method()).thenReturn(HttpMethod.GET);
        when(serverRequest.scheme()).thenReturn("http");
        when(serverRequest.protocol()).thenReturn("HTTP/1.1");

        ProxyRequest request = new ProxyRequest(serverRequest, null, null);

        RequestMatcher matcher = new RequestMatchParser("request.uri ~ \"/admin/.*\"").parse();
        assertThat(matcher.matches(request)).isTrue();
    }
}
