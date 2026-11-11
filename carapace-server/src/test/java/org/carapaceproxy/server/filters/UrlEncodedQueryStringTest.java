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

import static org.assertj.core.api.Assertions.assertThat;
import static org.carapaceproxy.server.filters.UrlEncodedQueryString.create;
import static org.carapaceproxy.server.filters.UrlEncodedQueryString.parse;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UrlEncodedQueryString} parsing/rendering, locking its sharper contract quirks
 * (case handling, valueless parameters, {@code ;} separators, form-decoding, multi-value).
 */
class UrlEncodedQueryStringTest {

    @Test
    void parsesMultiValuedParametersInOrder() {
        UrlEncodedQueryString q = parse("a=1&b=2&a=3");
        assertThat(q.getValues("a")).containsExactly("1", "3");
        assertThat(q.getValues("b")).containsExactly("2");
        assertThat(q.get("a")).isEqualTo("1"); // first value
    }

    @Test
    void splitsOnSemicolonAsWellAsAmpersand() {
        UrlEncodedQueryString q = parse("a=1;b=2");
        assertThat(q.get("a")).isEqualTo("1");
        assertThat(q.get("b")).isEqualTo("2");
    }

    @Test
    void namesAreLowercasedOnGetAndContainsButNotOnGetValues() {
        UrlEncodedQueryString q = parse("A=1");
        assertThat(q.get("a")).isEqualTo("1");
        assertThat(q.contains("a")).isTrue();
        assertThat(q.getValues("a")).containsExactly("1");
        // getValues does NOT lowercase its argument, and stored keys are lowercase:
        assertThat(q.getValues("A")).isNull();
    }

    @Test
    void valuelessParameterExistsButHasNullValue() {
        UrlEncodedQueryString q = parse("foo=1&bar");
        assertThat(q.contains("bar")).isTrue();
        assertThat(q.get("bar")).isNull();
        assertThat(q.get("foo")).isEqualTo("1");
        assertThat(q.toString()).contains("bar");
        assertThat(q.toString()).doesNotContain("bar=");
    }

    @Test
    void emptyValueParameterIsDistinctFromValueless() {
        UrlEncodedQueryString q = parse("foo=1&bar=");
        assertThat(q.contains("bar")).isTrue();
        assertThat(q.get("bar")).isEmpty();
    }

    @Test
    void formDecodesNamesAndValues() {
        assertThat(parse("%70age=1").get("page")).isEqualTo("1");
        assertThat(parse("a=x+y").get("a")).isEqualTo("x y");
        assertThat(parse("a=%20").get("a")).isEqualTo(" ");
    }

    @Test
    void appendAccumulatesSetReplacesRemoveDeletes() {
        UrlEncodedQueryString q = create();
        q.append("a", "1").append("a", "2");
        assertThat(q.getValues("a")).containsExactly("1", "2");

        q.set("a", "9");
        assertThat(q.getValues("a")).containsExactly("9");

        q.remove("a");
        assertThat(q.contains("a")).isFalse();
        assertThat(q.isEmpty()).isTrue();
    }

    @Test
    void setNullValueRemovesParameter() {
        UrlEncodedQueryString q = create();
        q.append("a", "1");
        q.set("a", (String) null);
        assertThat(q.contains("a")).isFalse();
    }

    @Test
    void toStringReEncodesAndRoundTrips() throws Exception {
        assertThat(parse("a=1&b=2")).hasToString("a=1&b=2");
        assertThat(create().append("a", "x y")).hasToString("a=x+y");

        URI applied = parse("a=1&b=2").apply(new URI("http://host/path"));
        assertThat(applied.getRawQuery()).isEqualTo("a=1&b=2");
    }

    @Test
    void equalityIsOrderSensitiveViaToString() {
        assertThat(parse("a=1&b=2")).isEqualTo(parse("a=1&b=2"));
        assertThat(parse("a=1&b=2")).isNotEqualTo(parse("b=2&a=1"));
    }
}
