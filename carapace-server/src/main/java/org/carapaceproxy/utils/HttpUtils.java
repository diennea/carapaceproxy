package org.carapaceproxy.utils;

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

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import reactor.netty.http.HttpProtocol;

/**
 * HTTP utilities for handling HTTP protocol versions and conversions.
 *
 * This class provides methods for working with HTTP protocols, including
 * converting between HTTP versions and protocol implementations, and ensuring
 * compatibility between protocol versions and transport security.
 *
 * @author paolo.venturi
 */
public class HttpUtils {

    private static final ZoneId GMT = ZoneId.of("GMT");

    /**
     * Standard hop-by-hop headers that must not be forwarded by a reverse proxy,
     * as defined by RFC 2616 §13.5.1 and RFC 7230 §6.1.
     * The {@code Proxy-Connection} header is a non-standard but widely-used extension that is also treated as hop-by-hop.
     *
     * <p>Note: {@code Transfer-Encoding} is intentionally excluded from this set. It is connection-specific
     * between adjacent peers, but Reactor Netty's HTTP/2 codec already enforces its removal at the wire
     * level (RFC 9113 §8.2.2). Stripping it here would break HTTP/1.1 chunked proxying by removing the
     * framing signal that clients need to reassemble the response body.
     */
    @SuppressWarnings("deprecation")
    private static final Set<CharSequence> HOP_BY_HOP_HEADERS = Set.of(
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.KEEP_ALIVE,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.PROXY_AUTHORIZATION,
            HttpHeaderNames.TE,
            HttpHeaderNames.TRAILER,
            HttpHeaderNames.UPGRADE,
            HttpHeaderNames.PROXY_CONNECTION
    );

    /**
     * Strips hop-by-hop headers from the given {@link HttpHeaders} in place.
     *
     * <p>A reverse proxy must not forward hop-by-hop headers between connections
     * (RFC 2616 §13.5.1, RFC 7230 §6.1). This method:
     * <ol>
     *   <li>Removes any headers dynamically nominated as hop-by-hop via the {@code Connection}
     *       header value (RFC 7230 §6.1).</li>
     *   <li>Removes the standard set of hop-by-hop headers.</li>
     * </ol>
     * This also ensures compliance with HTTP/2, which prohibits connection-specific headers
     * (RFC 9113 §8.2.2).
     *
     * @param headers the headers to strip in place (must be mutable)
     */
    public static void stripHopByHopHeaders(final HttpHeaders headers) {
        // Remove headers dynamically nominated via the Connection header (RFC 7230 §6.1)
        for (final String connectionValue : headers.getAll(HttpHeaderNames.CONNECTION)) {
            for (final String token : connectionValue.split(",")) {
                headers.remove(token.trim());
            }
        }
        // Remove standard hop-by-hop headers
        HOP_BY_HOP_HEADERS.forEach(headers::remove);
    }

    public static String formatDateHeader(java.util.Date date) {
        return RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(date.toInstant(), GMT));
    }

    public static HttpProtocol toHttpProtocol(final HttpVersion httpVersion, final boolean ssl) {
        return switch (httpVersion.majorVersion()) {
            case 1 -> HttpProtocol.HTTP11;
            case 2 -> ssl ? HttpProtocol.H2 : HttpProtocol.H2C;
            default -> throw new IllegalStateException("Unexpected HTTP Protocol: " + httpVersion);
        };
    }

    /**
     * Gets the appropriate HttpProtocol based on HTTP version and security.
     * This method ensures that the protocol is compatible with the SSL setting:
     * - HTTP/1.1 can be used with both HTTP and HTTPS
     * - HTTP/2 over TLS (H2) can only be used with HTTPS
     * - HTTP/2 cleartext (H2C) can only be used with HTTP
     *
     * @param httpVersion the HTTP version (HTTP/1.1 or HTTP/2)
     * @param ssl whether SSL/TLS is being used
     * @return the appropriate HttpProtocol
     */
    public static HttpProtocol getAppropriateProtocol(final HttpVersion httpVersion, final boolean ssl) {
        if (httpVersion.majorVersion() == 2) {
            return ssl ? HttpProtocol.H2 : HttpProtocol.H2C;
        }
        return HttpProtocol.HTTP11;
    }

    public static boolean mayHaveBody(final HttpHeaders requestHeaders) {
        final String transferEncoding = requestHeaders.get(HttpHeaderNames.TRANSFER_ENCODING);
        final String contentLength = requestHeaders.get(HttpHeaderNames.CONTENT_LENGTH);
        return (transferEncoding != null && transferEncoding.contains(HttpHeaderValues.CHUNKED.toString()))
                || (contentLength != null && Long.parseLong(contentLength) > 0);
    }
}
