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
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.ssl.SslHandler;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.carapaceproxy.server.config.NetworkListenerConfiguration.HostPort;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.server.mapper.requestmatcher.MatchingContext;
import org.reactivestreams.Publisher;
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

    private static final int HEADERS_SUBSTRING_INDEX = PROPERTY_HEADERS.length();
    private static final AtomicLong REQUESTS_ID_GENERATOR = new AtomicLong();

    private final long id = REQUESTS_ID_GENERATOR.incrementAndGet();
    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final HostPort listener;
    private MapResult action;
    private String userId;
    private String sessionId;
    private long startTs;
    private long backendStartTs = 0;
    private volatile long lastActivity;
    private String uri;
    private UrlEncodedQueryString queryString;
    private String sslProtocol;
    private String cipherSuite;
    @Getter
    @Setter
    private boolean servedFromCache;

    public ProxyRequest(HttpServerRequest request, HttpServerResponse response, HostPort listener) {
        this.request = request;
        this.response = response;
        this.listener = listener;
        request.withConnection(conn -> {
            SslHandler handler = conn.channel().pipeline().get(SslHandler.class);
            if (handler != null) {
                sslProtocol = handler.engine().getSession().getProtocol();
                cipherSuite = handler.engine().getSession().getCipherSuite();
            }
        });
    }

    @Override
    public String getProperty(String name) {
        if (name.startsWith(PROPERTY_HEADERS)) {
            // In case of multiple headers with same name, the first one is returned.
            return request.requestHeaders().get(name.substring(HEADERS_SUBSTRING_INDEX, name.length()), "");
        } else {
            switch (name) {
                case PROPERTY_URI:
                    return request.uri();
                case PROPERTY_METHOD:
                    return request.method().name();
                case PROPERTY_CONTENT_TYPE:
                    return request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE, "");
                case PROPERTY_LISTENER_IPADDRESS:
                    return getLocalAddress().getAddress().getHostAddress();
                case PROPERTY_LISTENER_HOST_PORT: {
                    return listener.getHost() + ":" + listener.getPort();
                }
                default: {
                    throw new IllegalArgumentException("Property name " + name + " does not exists.");
                }
            }
        }
    }

    @Override
    public boolean isSecure() {
        return request.scheme().equalsIgnoreCase(HttpScheme.HTTPS + "");
    }

    public InetSocketAddress getLocalAddress() {
        return request.hostAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return request.remoteAddress();
    }

    public String getUri() {
        if (uri != null) {
            return uri;
        }

        uri = request.uri();
        int pos = uri.indexOf("://");
        if (pos >= 0) {
            uri = uri.substring(pos + 3);
        }
        pos = uri.indexOf("/");
        if (pos >= 0) {
            uri = uri.substring(pos);
        } else {
            uri = "/";
        }

        return uri;
    }

    public String getRequestHostname() {
        return request.requestHeaders().get(HttpHeaderNames.HOST);
    }

    public UrlEncodedQueryString getQueryString() {
        if (queryString != null) {
            return queryString;
        }
        queryString = parseQueryString(request.uri());
        return queryString;
    }

    public static UrlEncodedQueryString parseQueryString(String uri) {
        int pos = uri.indexOf('?');
        return pos < 0 || pos == uri.length() - 1
                ? UrlEncodedQueryString.create()
                : UrlEncodedQueryString.parse(uri.substring(pos + 1));
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

    public Collection<Cookie> getRequestCookies() {
        return request.allCookies().values().stream() // cookies form client to endpoint
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public void setResponseCookies(Collection<Cookie> cookies) {
        cookies.forEach(cookie -> {
            response.addCookie(cookie);
        });
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
