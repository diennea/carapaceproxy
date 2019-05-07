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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import org.carapaceproxy.server.RequestHandler;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatcherTest {

    @Test
    public void test() throws ParseException, IOException {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/test.html");
        request.headers().add(RequestHandler.HEADER_X_FORWARDED_PROTO, "https");
        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertTrue(matcher instanceof MatchAllRequestMatcher);
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*'").parse();
            assertTrue(matcher instanceof URIRequestMatcher);
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*testio.*'").parse();
            assertTrue(matcher instanceof URIRequestMatcher);
            assertNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("https").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not https").parse();
            assertNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test\\.html' and https").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp      '.*test.*' and not https").parse();  // spaces ignored
            assertNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' or not https").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not https or https)").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not https or not https)").parse();
            assertNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and not (not https or not https)").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not (not https or not https) or (not https or not https))").parse();
            assertNotNull(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not (not https or not https) and (not https or not https))").parse();
            assertNull(matcher.matches(request));
        }

//
//        // First one correct considered
//        assertTrue(RequestMatcher.matches(request, "all regexp '.*test.*'"));
//        assertTrue(RequestMatcher.matches(request, "all regexp"));
//        assertTrue(RequestMatcher.matches(request, "all https"));
//        assertTrue(RequestMatcher.matches(request, "all not https"));
//        assertFalse(RequestMatcher.matches(request, "not https all"));
//        assertFalse(RequestMatcher.matches(request, "not https regexp '.*test.*'"));
//        assertTrue(RequestMatcher.matches(request, "regexp '.*test.*' all"));
//
//        // Broken ones
//        TestUtils.assertThrows(ParseException.class, () -> {
//            RequestMatcher.matches(request, "not https and regexp '.*test.*'");
//        });
//        TestUtils.assertThrows(ParseException.class, () -> {
//            RequestMatcher.matches(request, "not https and all");
//        });
//        TestUtils.assertThrows(ParseException.class, () -> {
//            RequestMatcher.matches(request, "regexp '.*test.*' and all");
//        });
    }
}
