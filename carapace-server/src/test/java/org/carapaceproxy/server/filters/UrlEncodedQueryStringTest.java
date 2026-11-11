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
package org.carapaceproxy.server.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UrlEncodedQueryString} parsing/rendering, locking its sharper contract quirks
 * (case handling, valueless parameters, {@code ;} separators, form-decoding, multi-value).
 */
class UrlEncodedQueryStringTest {

    @Test
    void parsesMultiValuedParametersInOrder() {
        UrlEncodedQueryString q = UrlEncodedQueryString.parse("a=1&b=2&a=3");
        assertEquals(List.of("1", "3"), q.getValues("a"));
        assertEquals(List.of("2"), q.getValues("b"));
        assertEquals("1", q.get("a")); // first value
    }

    @Test
    void splitsOnSemicolonAsWellAsAmpersand() {
        UrlEncodedQueryString q = UrlEncodedQueryString.parse("a=1;b=2");
        assertEquals("1", q.get("a"));
        assertEquals("2", q.get("b"));
    }

    @Test
    void namesAreLowercasedOnGetAndContainsButNotOnGetValues() {
        UrlEncodedQueryString q = UrlEncodedQueryString.parse("A=1");
        assertEquals("1", q.get("a"));
        assertTrue(q.contains("a"));
        assertEquals(List.of("1"), q.getValues("a"));
        // getValues does NOT lowercase its argument, and stored keys are lowercase:
        assertNull(q.getValues("A"));
    }

    @Test
    void valuelessParameterExistsButHasNullValue() {
        UrlEncodedQueryString q = UrlEncodedQueryString.parse("foo=1&bar");
        assertTrue(q.contains("bar"));
        assertNull(q.get("bar"));
        assertEquals("1", q.get("foo"));
        assertTrue(q.toString().contains("bar"));
        assertFalse(q.toString().contains("bar="));
    }

    @Test
    void emptyValueParameterIsDistinctFromValueless() {
        UrlEncodedQueryString q = UrlEncodedQueryString.parse("foo=1&bar=");
        assertTrue(q.contains("bar"));
        assertTrue(q.get("bar").isEmpty());
    }

    @Test
    void formDecodesNamesAndValues() {
        assertEquals("1", UrlEncodedQueryString.parse("%70age=1").get("page"));
        assertEquals("x y", UrlEncodedQueryString.parse("a=x+y").get("a"));
        assertEquals(" ", UrlEncodedQueryString.parse("a=%20").get("a"));
    }

    @Test
    void appendAccumulatesSetReplacesRemoveDeletes() {
        UrlEncodedQueryString q = UrlEncodedQueryString.create();
        q.append("a", "1").append("a", "2");
        assertEquals(List.of("1", "2"), q.getValues("a"));

        q.set("a", "9");
        assertEquals(List.of("9"), q.getValues("a"));

        q.remove("a");
        assertFalse(q.contains("a"));
        assertTrue(q.isEmpty());
    }

    @Test
    void setNullValueRemovesParameter() {
        UrlEncodedQueryString q = UrlEncodedQueryString.create();
        q.append("a", "1");
        q.set("a", (String) null);
        assertFalse(q.contains("a"));
    }

    @Test
    void toStringReEncodesAndRoundTrips() throws Exception {
        assertEquals("a=1&b=2", UrlEncodedQueryString.parse("a=1&b=2").toString());
        assertEquals("a=x+y", UrlEncodedQueryString.create().append("a", "x y").toString());

        URI applied = UrlEncodedQueryString.parse("a=1&b=2").apply(new URI("http://host/path"));
        assertEquals("a=1&b=2", applied.getRawQuery());
    }

    @Test
    void equalityIsOrderSensitiveViaToString() {
        assertEquals(UrlEncodedQueryString.parse("a=1&b=2"), UrlEncodedQueryString.parse("a=1&b=2"));
        assertNotEquals(UrlEncodedQueryString.parse("b=2&a=1"), UrlEncodedQueryString.parse("a=1&b=2"));
    }
}
