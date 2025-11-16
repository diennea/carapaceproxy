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
package org.carapaceproxy.core;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.Data;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.server.mapper.requestmatcher.MatchingContext;
import org.carapaceproxy.utils.StringUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * A proxy request
 *
 * @author paolo.venturi
 */
@Data
public class ProxyRequest implements MatchingContext {

    // All properties name have been converted to lowercase during parsing
    public static final String PROPERTY_URI = "request.uri";
    public static final String PROPERTY_METHOD = "request.method";
    public static final String PROPERTY_CONTENT_TYPE = "request.content-type";
    public static final String PROPERTY_HEADERS = "request.headers.";
    public static final String PROPERTY_LISTENER_HOST_PORT = "listener.hostport";
    public static final String PROPERTY_LISTENER_IPADDRESS = "listener.ipaddress";
    private static final Pattern NETTY_HTTP_1_VERSION_PATTERN = Pattern.compile("(\\S+)/(\\d+)\\.(\\d+)");
    private static final int HEADERS_SUBSTRING_INDEX = PROPERTY_HEADERS.length();
    private static final AtomicLong REQUESTS_ID_GENERATOR = new AtomicLong();
    private static final Logger LOG = LoggerFactory.getLogger(ProxyRequest.class);

    private final long id = REQUESTS_ID_GENERATOR.incrementAndGet();
    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final EndpointKey listener;
    private final HttpVersion httpProtocol;
    private final String sslProtocol;
    private final String cipherSuite;
    private MapResult action;
    private String userId;
    private String sessionId;
    private long startTs;
    private long backendStartTs = 0;
    private volatile long lastActivity;
    private String uri;
    private UrlEncodedQueryString queryString;
    private boolean servedFromCache;

    public ProxyRequest(
            final HttpServerRequest request, final HttpServerResponse response, final EndpointKey listener
    ) {
        this.request = request;
        this.response = response;
        this.listener = listener;
        final var protocol = request.protocol();
        if (NETTY_HTTP_1_VERSION_PATTERN.matcher(protocol).matches()) {
            this.httpProtocol = HttpVersion.valueOf(protocol);
        } else if (!protocol.contains(".")) {
            this.httpProtocol = HttpVersion.valueOf(protocol + ".0");
        } else {
            throw new IllegalArgumentException("Unsupported request protocol: " + protocol);
        }
        final SslHandler handler = getSslHandler();
        if (handler != null) {
            this.sslProtocol = handler.engine().getSession().getProtocol();
            this.cipherSuite = handler.engine().getSession().getCipherSuite();
        } else {
            this.sslProtocol = null;
            this.cipherSuite = null;
        }
    }

    public static UrlEncodedQueryString parseQueryString(final String uri) {
        int pos = uri.indexOf('?');
        return pos < 0 || pos == uri.length() - 1
                ? UrlEncodedQueryString.create()
                : UrlEncodedQueryString.parse(uri.substring(pos + 1));
    }

    private SslHandler getSslHandler() {
        final AtomicReference<SslHandler> sslHandlerRef = new AtomicReference<>();
        request.withConnection(conn -> sslHandlerRef.set(conn.channel().pipeline().get(SslHandler.class)));
        return sslHandlerRef.get();
    }

    @Override
    public String getProperty(String name) {
        if (name.startsWith(PROPERTY_HEADERS)) {
            // In case of multiple headers with the same name, the first one is returned.
            return request.requestHeaders().get(name.substring(HEADERS_SUBSTRING_INDEX), "");
        }
        return switch (name) {
            case PROPERTY_URI -> request.uri();
            case PROPERTY_METHOD -> request.method().name();
            case PROPERTY_CONTENT_TYPE -> request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE, "");
            case PROPERTY_LISTENER_IPADDRESS -> getLocalAddress().getAddress().getHostAddress();
            case PROPERTY_LISTENER_HOST_PORT -> listener.host() + ":" + listener.port();
            default -> throw new IllegalArgumentException("Property name " + name + " does not exists.");
        };
    }

    @Override
    public boolean isSecure() {
        return HttpScheme.HTTPS.name().contentEqualsIgnoreCase(request.scheme());
    }

    public InetSocketAddress getLocalAddress() {
        return Objects.requireNonNull(request.hostAddress());
    }

    public InetSocketAddress getRemoteAddress() {
        return Objects.requireNonNull(request.remoteAddress());
    }

    /**
     * @return the uri path (starting from /) + query params
     */
    public String getUri() {
        if (uri != null) {
            return uri;
        }

        uri = request.uri();

        String schemePrefix = request.scheme() + "://";
        // uri in the form of scheme://domain:port/
        // > we need to avoid open-proxy vulnerability
        if (uri.startsWith(schemePrefix)) {
            uri = uri.substring(schemePrefix.length());
        }
        String fullPath = request.fullPath();
        if (StringUtils.isBlank(fullPath)) {
            int queryStringPos = uri.indexOf("?");
            if (queryStringPos >= 0) {
                uri = "/" + uri.substring(queryStringPos);
            } else {
                uri = "/";
            }
        } else {
            int pos = uri.indexOf(request.fullPath());
            if (pos > 0) {
                uri = uri.substring(pos);
            }
        }

        return uri;
    }

    public String getScheme() {
        return request.scheme();
    }

    /**
     * Get the currently defined hostname, including port if provided.
     * It leverages {@code :authority} pseudo-header over HTTP/2 and {@code HOST} header over HTTP/1.1 and older.
     * <br>
     * It doesn't use {@link HttpServerRequest#hostName()},
     * nor it considers {@code X-Forwarded-Host}/{@code Forwarded} headers.
     *
     * @return the hostname and possibly the port of the request;
     * it may be null over HTTP/1.1 if no {@code HOST} header is provided
     */
    public String getRequestHostname() {
        final String virtualHostName = request.hostName();
        final HttpHeaders httpHeaders = request.requestHeaders();
        // The Host request header specifies the host and port number of the server to which the request is being sent.
        // If no port is included, the default port for the service requested is implied
        // Host: <host>:<port>
        final String headerHostName = httpHeaders.get(HttpHeaderNames.HOST);
        final String protocol = request.protocol().toUpperCase();
        if (StringUtils.isBlank(headerHostName)) {
            if (HttpVersion.valueOf(protocol).majorVersion() == 2) {
                // RFC 3986 section 3.2 states that :authority may include port if provided, just like HTTP/1.1 HOST header
                // authority = [ userinfo "@" ] host [ ":" port ]
                final AsciiString authorityHeaderName = Http2Headers.PseudoHeaderName.AUTHORITY.value();
                final String authority = httpHeaders.get(authorityHeaderName);
                if (!StringUtils.isBlank(authority)) {
                    // Reactor Netty usually strips pseudo-headers from the request, filling "traditional" ones...
                    LOG.warn("Unexpected fallback to authority header; available headers are {}", httpHeaders);
                    return authority;
                }
            }
            LOG.warn(
                    "Received request over protocol {}, but {} header was not set; using {} recognized by Netty. Available headers are {}",
                    protocol,
                    HttpHeaderNames.HOST,
                    virtualHostName,
                    httpHeaders
            );
            return virtualHostName;
        }
        return headerHostName;
    }

    public UrlEncodedQueryString getQueryString() {
        if (queryString != null) {
            return queryString;
        }
        queryString = parseQueryString(request.uri());
        return queryString;
    }

    public HttpHeaders getRequestHeaders() {
        return request.requestHeaders();
    }

    public HttpHeaders getResponseHeaders() {
        return response.responseHeaders();
    }

    public void setResponseHeaders(HttpHeaders headers) {
        response.headers(headers);
    }

    public HttpMethod getMethod() {
        return request.method();
    }

    public ByteBufFlux getRequestData() {
        return request.receive().retain();
    }

    public Publisher<Void> send() {
        return response.send();
    }

    public Publisher<Void> sendResponseData(Publisher<? extends ByteBuf> data) {
        return response.send(data);
    }

    public void setResponseStatus(HttpResponseStatus status) {
        response.status(status);
    }

    public boolean isKeepAlive() {
        return request.isKeepAlive();
    }

}
