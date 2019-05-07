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
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.mapper.requestmatcher.parser.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.parser.RequestMatchParser;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatcherTest {

    @Test
    public void test() throws Exception {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/test.html");
        request.headers().add(RequestHandler.HEADER_X_FORWARDED_PROTO, "https");
        {
            RequestMatcher matcher = new RequestMatchParser("all").parse();
            assertTrue(matcher instanceof MatchAllRequestMatcher);
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*'").parse();
            assertTrue(matcher instanceof URIRequestMatcher);
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*testio.*'").parse();
            assertTrue(matcher instanceof URIRequestMatcher);
            assertFalse(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("https").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("not https").parse();
            assertFalse(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test\\.html' and https").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp      '.*test.*' and not https").parse();  // spaces ignored
            assertFalse(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' or not https").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not https or https)").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not https or not https)").parse();
            assertFalse(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and not (not https or not https)").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not (not https or not https) or (not https or not https))").parse();
            assertTrue(matcher.matches(request));
        }
        {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and (not (not https or not https) and (not https or not https))").parse();
            assertFalse(matcher.matches(request));
        }

        // Broken ones: all not alone
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all regexp '.*test.*'").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all regexp").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all https").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("all not https").parse();
        });
        // Broken ones: regexp not alone
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' all").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("regexp '.*test.*' and all").parse();
        });
        // Broken ones: condition with consequential regexp/all
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("not https and regexp '.*test.*'").parse();
        });
        TestUtils.assertThrows(ParseException.class, () -> {
            RequestMatcher matcher = new RequestMatchParser("not https or all").parse();
        });

        // Fist one condition considered
        assertFalse(new RequestMatchParser("not https all").parse().matches(request));
        assertFalse(new RequestMatchParser("not https regexp '.*test.*'").parse().matches(request));
    }
}
