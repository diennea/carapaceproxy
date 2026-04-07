package org.carapaceproxy.utils;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Test;

public class HttpUtilsTest {

    @Test
    public void testStripHopByHopHeaders_standardHeaders() {
        final HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(HttpHeaderNames.HOST, "example.com");
        headers.add(HttpHeaderNames.CONTENT_TYPE, "text/html");
        headers.add(HttpHeaderNames.CONNECTION, "keep-alive");
        headers.add(HttpHeaderNames.KEEP_ALIVE, "timeout=5, max=100");
        headers.add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        headers.add(HttpHeaderNames.UPGRADE, "websocket");
        headers.add(HttpHeaderNames.TE, "trailers");
        headers.add(HttpHeaderNames.TRAILER, "Expires");
        headers.add(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"test\"");
        headers.add(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic dXNlcjpwYXNz");
        headers.add("proxy-connection", "keep-alive");

        HttpUtils.stripHopByHopHeaders(headers);

        // End-to-end headers must be preserved
        assertThat(headers.get(HttpHeaderNames.HOST), is("example.com"));
        assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE), is("text/html"));

        // Hop-by-hop headers must be stripped
        assertThat(headers.get(HttpHeaderNames.CONNECTION), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.KEEP_ALIVE), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.UPGRADE), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.TE), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.TRAILER), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.PROXY_AUTHENTICATE), is(nullValue()));
        assertThat(headers.get(HttpHeaderNames.PROXY_AUTHORIZATION), is(nullValue()));
        assertThat(headers.get("proxy-connection"), is(nullValue()));

        // Transfer-Encoding is intentionally NOT stripped by stripHopByHopHeaders:
        // Reactor Netty's HTTP/2 codec enforces its removal at the wire level (RFC 9113 §8.2.2),
        // and stripping it here would break HTTP/1.1 chunked proxying.
        assertThat(headers.get(HttpHeaderNames.TRANSFER_ENCODING), is("chunked"));
    }

    @Test
    public void testStripHopByHopHeaders_dynamicNomination() {
        // RFC 7230 §6.1: any header listed in the Connection header value is also hop-by-hop
        final HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(HttpHeaderNames.HOST, "example.com");
        headers.add("x-custom-connection-option", "some-value");
        headers.add("another-option", "another-value");
        headers.add(HttpHeaderNames.CONNECTION, "x-custom-connection-option, another-option");

        HttpUtils.stripHopByHopHeaders(headers);

        assertThat(headers.get(HttpHeaderNames.HOST), is("example.com"));
        // Both the Connection header itself and the dynamically nominated headers must be stripped
        assertThat(headers.get(HttpHeaderNames.CONNECTION), is(nullValue()));
        assertThat(headers.get("x-custom-connection-option"), is(nullValue()));
        assertThat(headers.get("another-option"), is(nullValue()));
    }

    @Test
    public void testStripHopByHopHeaders_multipleConnectionValues() {
        // Connection header may have multiple values across multiple header entries
        final HttpHeaders headers = new DefaultHttpHeaders(false);  // false = allow duplicate header names
        headers.add(HttpHeaderNames.HOST, "example.com");
        headers.add("opt-a", "val-a");
        headers.add("opt-b", "val-b");
        headers.add(HttpHeaderNames.CONNECTION, "opt-a");
        headers.add(HttpHeaderNames.CONNECTION, "opt-b");

        HttpUtils.stripHopByHopHeaders(headers);

        assertThat(headers.get(HttpHeaderNames.HOST), is("example.com"));
        assertThat(headers.get(HttpHeaderNames.CONNECTION), is(nullValue()));
        assertThat(headers.get("opt-a"), is(nullValue()));
        assertThat(headers.get("opt-b"), is(nullValue()));
    }

    @Test
    public void testStripHopByHopHeaders_noHopByHopHeaders() {
        // Headers with no hop-by-hop headers are left unchanged
        final HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(HttpHeaderNames.HOST, "example.com");
        headers.add(HttpHeaderNames.CONTENT_TYPE, "application/json");
        headers.add(HttpHeaderNames.CONTENT_LENGTH, "42");

        HttpUtils.stripHopByHopHeaders(headers);

        assertThat(headers.get(HttpHeaderNames.HOST), is("example.com"));
        assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE), is("application/json"));
        assertThat(headers.get(HttpHeaderNames.CONTENT_LENGTH), is("42"));
    }

    @Test
    public void testStripHopByHopHeaders_emptyHeaders() {
        final HttpHeaders headers = new DefaultHttpHeaders();
        HttpUtils.stripHopByHopHeaders(headers);
        assertThat(headers.isEmpty(), is(true));
    }
}
