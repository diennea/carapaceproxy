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

import static org.carapaceproxy.server.mapper.MapResult.REDIRECT_PROTO_HTTP;
import static org.carapaceproxy.server.mapper.MapResult.REDIRECT_PROTO_HTTPS;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.mapper.CustomHeader;
import org.carapaceproxy.utils.PrometheusUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Manager forwarding {@link ProxyRequest} from clients to proper endpoints.
 *
 * @author paolo.venturi
 */
public class ProxyRequestsManager {

    private static final Logger LOGGER = Logger.getLogger(ProxyRequestsManager.class.getName());

    public static final Counter USER_REQUESTS_COUNTER = PrometheusUtils.createCounter(
            "listeners", "user_requests_total", "inbound requests count", "userId"
    ).register();

    public static final Gauge PENDING_REQUESTS_GAUGE = PrometheusUtils.createGauge(
            "backends", "pending_requests", "pending requests"
    ).register();

    public static final Counter TOTAL_REQUESTS_COUNTER = PrometheusUtils.createCounter(
            "backends", "sent_requests_total", "sent requests", "host"
    ).register();

    public static final Counter STUCK_REQUESTS_COUNTER = PrometheusUtils.createCounter(
            "backends", "stuck_requests_total", "stuck requests, this requests will be killed"
    ).register();

    private final HttpProxyServer parent;
    private ConnectionProvider connectionProvider;
    private final ConcurrentHashMap<EndpointKey, EndpointStats> endpointsStats = new ConcurrentHashMap<>();

    public ProxyRequestsManager(HttpProxyServer parent) {
        this.parent = parent;
    }

    public ConcurrentHashMap<EndpointKey, EndpointStats> getEndpointsStats() {
        return endpointsStats;
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        if (connectionProvider == null || (newConfiguration.getBorrowTimeout() != parent.getCurrentConfiguration().getBorrowTimeout()
                || newConfiguration.getMaxConnectionsPerEndpoint() != parent.getCurrentConfiguration().getMaxConnectionsPerEndpoint())) {
            close();
            ConnectionProvider.Builder builder = ConnectionProvider.builder("custom")
                    .pendingAcquireTimeout(Duration.ofSeconds(newConfiguration.getBorrowTimeout()));
            parent.getMapper().getBackends().values().forEach(be -> {
                builder.forRemoteHost(new InetSocketAddress(be.getHost(), be.getPort()), spec -> spec.maxConnections(newConfiguration.getMaxConnectionsPerEndpoint()));
            });
            connectionProvider = builder.build();
        }
    }

    public void close() {
        if (connectionProvider != null) {
            connectionProvider.disposeLater().block();
            connectionProvider = null;
        }
    }

    public Publisher<Void> processRequest(ProxyRequest request) {
        request.setStartTs(System.currentTimeMillis());
        request.setLastActivity(request.getStartTs());

        for (RequestFilter filter : parent.getFilters()) {
            filter.apply(request);
        }

        MapResult action = parent.getMapper().map(request);
        if (action == null) {
            LOGGER.log(Level.INFO, "Mapper returned NULL action for {0}", this);
            action = MapResult.internalError(MapResult.NO_ROUTE);
        }
        request.setAction(action);

        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "{0} Mapped {1} to {2}, userid {3}", new Object[]{this, request.getUri(), action, request.getUserId()});
        }

        String endpointHost = action.host;
        int endpointPort = action.port;

        Counter.Child requestsPerUser = request.getUserId() != null
                ? USER_REQUESTS_COUNTER.labels(request.getUserId())
                : USER_REQUESTS_COUNTER.labels("anonymous");
        Counter.Child totalRequests = TOTAL_REQUESTS_COUNTER.labels(request.getHostPort() + "");

        switch (action.action) {
            case NOTFOUND:
                return serveNotFoundMessage(request);

            case INTERNAL_ERROR:
                return serveInternalErrorMessage(request);

            case STATIC:
            case ACME_CHALLENGE:
                return serveStaticMessage(request);

            case REDIRECT:
                return serveRedirect(request);

            case PROXY: {
                final EndpointStats endpointStats = endpointsStats.computeIfAbsent(EndpointKey.make(endpointHost, endpointPort), EndpointStats::new);
                HttpClient client = HttpClient.create(connectionProvider)
                        .host(action.host)
                        .port(action.port)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, parent.getCurrentConfiguration().getConnectTimeout())
                        .doOnChannelInit((observer, channel, remoteAddress) -> {
                            channel.pipeline().addFirst("readTimeoutHandler", new ReadTimeoutHandler(parent.getCurrentConfiguration().getIdleTimeout() / 2));
                            channel.pipeline().addFirst("writeTimeoutHandler", new WriteTimeoutHandler(parent.getCurrentConfiguration().getIdleTimeout() / 2));
                        })
                        .doOnRequest((req, conn) -> {
                            requestsPerUser.inc();
                            PENDING_REQUESTS_GAUGE.inc();
                            totalRequests.inc();
                            endpointStats.getTotalRequests().incrementAndGet();
                            endpointStats.getLastActivity().set(System.currentTimeMillis());
                            parent.getRequestsLogger().logRequest(request);
                        })
                        .doOnRequestError((req, err) -> {
                            LOGGER.log(Level.INFO, "errorSendingRequest to " + request.getAction().host + ":" + request.getAction().port, err);
                            PENDING_REQUESTS_GAUGE.dec();
                            serveServiceNotAvailable(request);
                        })
                        .doAfterResponseSuccess((resp, conn) -> {
                            PENDING_REQUESTS_GAUGE.dec();
                            endpointStats.getLastActivity().set(System.currentTimeMillis());
                        })
                        .doOnResponseError((req, err)
                                -> parent.getBackendHealthManager().reportBackendUnreachable(endpointHost + ":" + endpointPort, System.currentTimeMillis(), "Response failed: " + err)
                        )
                        .followRedirect(true)
                        .headers(h -> h.add(request.getRequestHeaders()));

                for (Cookie cookie : request.getRequestCookies()) {
                    client = client.cookie(cookie);
                }

                return client.request(request.getMethod())
                        .uri(request.getUri())
                        .send(request.getRequestData()) // client request body
                        .response((resp, flux) -> { // endpoint response
                            request.setResponseStatus(resp.status());
                            request.setResponseHeaders(resp.responseHeaders()); // headers from endpoint to client
                            addCustomResponseHeaders(request, request.getAction().customHeaders);
                            request.setResponseCookies(resp.cookies().values().stream() // cookies from endpoint to client
                                    .flatMap(Collection::stream)
                                    .collect(Collectors.toList())
                            );
                            return request.sendResponseData(flux.retain().doOnNext(data -> { // response data
                                request.setLastActivity(System.currentTimeMillis());
                                endpointStats.getLastActivity().set(System.currentTimeMillis());
                            }));
                        });

            }

            case CACHE: {
//                cacheSender = connectionToClient.cache.serveFromCache(this);
//                if (cacheSender != null) {
//                    return;
//                }
//                connectionToEndpoint.set(connectionToClient.getConnectionToEndpoint()); // existing client2endpoint connection
//                if (connectionToEndpoint.get() == null) {
//                    try {
//                        connectionToEndpoint.set(connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port)));
//                        connectionToClient.setConnectionToEndpoint(connectionToEndpoint.get());
//                        channelToClient.channel().closeFuture().addListener((Future<? super Void> future) -> {
//                            CarapaceLogger.debug("Closing channel to client id={0}.", connectionToClient.getId());
//                            EndpointConnection endpointConn = connectionToEndpoint.get();
//                            if (endpointConn != null) {
//                                fireRequestFinished();
//                                // return the connection the pool (async)
//                                endpointConn.release(closeAfterResponse, this, () -> connectionToEndpoint.compareAndSet(endpointConn, null));
//                            } else {
//                                LOG.log(Level.SEVERE, "{0} CANNOT release connection {1}, closeAfterResponse {2}", new Object[]{this, connectionToEndpoint, closeAfterResponse});
//                            }
//                        });
//                    } catch (EndpointNotAvailableException err) {
//                        fireRequestFinished();
//                        LOG.log(Level.INFO, "{0} error on endpoint {1}: {2}", new Object[]{this, action, err});
//                        return;
//                    }
//                }
//                cacheReceiver = connectionToClient.cache.startCachingResponse(request, isSecure());
//                if (cacheReceiver != null) {
//                    // https://tools.ietf.org/html/rfc7234#section-4.3.4
//                    cleanRequestFromCacheValidators(request);
//                }
//                connectionToEndpoint.get().sendRequest(request, this);
//                return;

//            if (cacheSender != null) {
//            serveFromCache();
//if (request.getc != null && !continueRequest) {
//                                // msg object won't be cached as-is but the cache will retain a clone of it
//                                cacheReceiver.receivedFromRemote(msg);
//                                if (msg instanceof HttpResponse) {
//                                    HttpResponse httpMessage = (HttpResponse) msg;
//                                    cleanResponseForCachedData(httpMessage);
//                                }
//                            }
                return Mono.empty();
            }

            default:
                throw new IllegalStateException("Action %s not supported".formatted(action.action));
        }
    }

    private Publisher<Void> serveNotFoundMessage(ProxyRequest request) {
        SimpleHTTPResponse res = parent.getMapper().mapPageNotFound(request.getAction().routeId);
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.getErrorcode();
            resource = res.getResource();
            customHeaders = res.getCustomHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_NOT_FOUND;
        }
        if (code <= 0) {
            code = 404;
        }
        FullHttpResponse response = parent.getStaticContentsManager().buildResponse(code, resource);

        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveInternalErrorMessage(ProxyRequest request) {
        SimpleHTTPResponse res = parent.getMapper().mapInternalError(request.getAction().routeId);
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.getErrorcode();
            resource = res.getResource();
            customHeaders = res.getCustomHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
        }
        if (code <= 0) {
            code = 500;
        }
        FullHttpResponse response = parent.getStaticContentsManager().buildResponse(code, resource);

        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveStaticMessage(ProxyRequest request) {
        FullHttpResponse response = parent.getStaticContentsManager()
                .buildResponse(request.getAction().errorCode, request.getAction().resource);

        return writeSimpleResponse(request, response);
    }

    private Publisher<Void> serveRedirect(ProxyRequest request) {
        MapResult action = request.getAction();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(action.errorCode < 0 ? 302 : action.errorCode) // redirect: 3XX
        );
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

        String location = action.redirectLocation;
        String host = request.getRequestHeaders().get(HttpHeaderNames.HOST, "localhost");
        String port = host.contains(":") ? host.replaceFirst(".*:", ":") : "";
        host = host.split(":")[0];
        String path = request.getUri();
        if (location == null || location.isEmpty()) {
            if (!action.host.isEmpty()) {
                host = action.host;
            }
            if (action.port > 0) {
                port = ":" + action.port;
            } else if (REDIRECT_PROTO_HTTPS.equals(action.redirectProto)) {
                port = ""; // default https port
            }
            if (!action.redirectPath.isEmpty()) {
                path = action.redirectPath;
            }
            location = host + port + path; // - custom redirection
        } else if (location.startsWith("/")) {
            location = host + port + location; // - relative redirection
        } // else: implicit absolute redirection

        // - redirect to https
        location = (REDIRECT_PROTO_HTTPS.equals(action.redirectProto) ? REDIRECT_PROTO_HTTPS : REDIRECT_PROTO_HTTP)
                + "://" + location.replaceFirst("http.?:\\/\\/", "");
        response.headers().set(HttpHeaderNames.LOCATION, location);

        return writeSimpleResponse(request, response);
    }

    private Publisher<Void> serveServiceNotAvailable(ProxyRequest request) {
        FullHttpResponse response = parent.getStaticContentsManager().buildServiceNotAvailableResponse();
        return writeSimpleResponse(request, response);
    }

    private static Publisher<Void> writeSimpleResponse(ProxyRequest request, FullHttpResponse response) {
        return writeSimpleResponse(request, response, request.getAction().customHeaders);
    }

    private static Publisher<Void> writeSimpleResponse(ProxyRequest request, FullHttpResponse response, List<CustomHeader> customHeaders) {
//        if (headerSent) {
//            LOGGER.log(Level.INFO, "{0}: headers for already sent to client, cannot send static response", this);
//            return true;
//        }

        // Prepare the response
        if (request.isKeepAlive()) {
            // Add 'Content-Length' header only for a keep-alive connection.
            if (response.content() != null) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        request.setResponseStatus(response.status());
        request.setResponseHeaders(response.headers());
        addCustomResponseHeaders(request, customHeaders);

        // Write the response
        return request.sendResponseData(Mono.just(response.content()).doFinally(f -> request.setLastActivity(System.currentTimeMillis())));
    }

    private static void addCustomResponseHeaders(ProxyRequest request, List<CustomHeader> customHeaders) {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return;
        }
        HttpHeaders headers = request.getResponseHeaders();
        customHeaders.forEach(customHeader -> {
            if (CustomHeader.HeaderMode.SET.equals(customHeader.getMode())
                    || CustomHeader.HeaderMode.REMOVE.equals(customHeader.getMode())) {
                headers.remove(customHeader.getName());
            }
            if (CustomHeader.HeaderMode.SET.equals(customHeader.getMode())
                    || CustomHeader.HeaderMode.ADD.equals(customHeader.getMode())) {
                headers.add(customHeader.getName(), customHeader.getValue());
            }
        });
    }

//
//    public void serveFromCache() {
//        ContentsCache.ContentPayload payload = cacheSender.getCached();
//        sendCachedChunk(payload, 0);
//    }
//
//    private void sendCachedChunk(ContentsCache.ContentPayload payload, int i) {
//        int size = payload.getChunks().size();
//        HttpObject object = payload.getChunks().get(i);
//        boolean notModified;
//        boolean isLastHttpContent = object instanceof LastHttpContent;
//        if (object instanceof HttpResponse) {
//
//            HttpHeaders requestHeaders = request.headers();
//            long ifModifiedSince = requestHeaders.getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, -1);
//            if (ifModifiedSince != -1 && payload.getLastModified() > 0 && ifModifiedSince >= payload.getLastModified()) {
//                HttpHeaders newHeaders = new DefaultHttpHeaders();
//                newHeaders.set("Last-Modified", new java.util.Date(payload.getLastModified()));
//                newHeaders.set("Expires", new java.util.Date(payload.getExpiresTs()));
//                newHeaders.add("X-Cached", "yes; ts=" + payload.getCreationTs());
//                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED,
//                        Unpooled.EMPTY_BUFFER, newHeaders, new DefaultHttpHeaders());
//                object = resp;
//                notModified = true;
//            } else {
//                HttpResponse resp = (HttpResponse) object;
//                HttpHeaders headers = new DefaultHttpHeaders();
//                headers.add(resp.headers());
//                headers.remove(HttpHeaderNames.EXPIRES);
//                headers.remove(HttpHeaderNames.ACCEPT_RANGES);
//                headers.remove(HttpHeaderNames.ETAG);
//                headers.add("X-Cached", "yes; ts=" + payload.getCreationTs());
//                headers.add("Expires", new java.util.Date(payload.getExpiresTs()));
//
//                object = new DefaultHttpResponse(resp.protocolVersion(), resp.status(), headers);
//                long contentLength = HttpUtil.getContentLength(resp, -1);
//                String transferEncoding = resp.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
//                if (contentLength < 0 && !"chunked".equals(transferEncoding)) {
//                    connectionToClient.keepAlive = false;
//                    LOGGER.log(Level.SEVERE, uri + " response without contentLength{0} and with Transfer-Encoding {1}. keepalive will be disabled " + resp,
//                            new Object[]{contentLength, transferEncoding});
//                }
//                notModified = false;
//            }
//        } else {
//            object = ContentsCache.cloneHttpObject(object);
//            notModified = false;
//        }
//        HttpObject _object = object;
//        clientState = ProxyRequestsManager.RequestHandlerState.WRITING;
//        channelToClient.writeAndFlush(_object)
//                .addListener((g) -> {
//                    clientState = ProxyRequestsManager.RequestHandlerState.IDLE;
//                    if (isLastHttpContent || notModified) {
//                        requestComplete();
//                        connectionToClient.closeIfNotKeepAlive(channelToClient);
//                    }
//                    if (i + 1 < size && g.isSuccess() && !notModified) {
//                        sendCachedChunk(payload, i + 1);
//                    } else {
//                        fireRequestFinished();
//                    }
//                });
//    }
//
//    private void cleanRequestFromCacheValidators(HttpRequest request) {
//        HttpHeaders headers = request.headers();
//        headers.remove(HttpHeaderNames.IF_MATCH);
//        headers.remove(HttpHeaderNames.IF_MODIFIED_SINCE);
//        headers.remove(HttpHeaderNames.IF_NONE_MATCH);
//        headers.remove(HttpHeaderNames.IF_RANGE);
//        headers.remove(HttpHeaderNames.IF_UNMODIFIED_SINCE);
//        headers.remove(HttpHeaderNames.ETAG);
//        headers.remove(HttpHeaderNames.CONNECTION);
//    }
//
//    private void cleanResponseForCachedData(HttpResponse httpMessage) {
//        HttpHeaders headers = httpMessage.headers();
//        if (!headers.contains(HttpHeaderNames.EXPIRES)) {
//            headers.add("Expires", new java.util.Date(connectionToClient.cache.computeDefaultExpireDate()));
//        }
//    }
//
//    public void failIfStuck(long now, int stuckRequestTimeout, Runnable onStuck) {
//        long delta = now - lastActivity;
//        if (delta >= stuckRequestTimeout) {
//            LOGGER.log(Level.INFO, "{0} connection appears stuck {1}, on request {2} for userId: {3}", new Object[]{this, connectionToEndpoint, uri, userId});
//            onStuck.run();
//            serveInternalErrorMessage(true);
//        }
//    }
//
//    public void badErrorOnRemote(Throwable cause) {
//        LOGGER.log(Level.INFO, "{0} badErrorOnRemote {1}", new Object[]{this, cause});
//        serveInternalErrorMessage(true);
//    }
//
//    private class RequestHandlerChecker implements Runnable {
//
//        @Override
//        public void run() {
//            long now = System.currentTimeMillis();
//            List<RequestHandler> toRemove = new ArrayList<>();
//            for (Map.Entry<Long, RequestHandler> entry : pendingRequests.entrySet()) {
//                RequestHandler requestHandler = entry.getValue();
//                requestHandler.failIfStuck(now, stuckRequestTimeout, () -> {
//                    EndpointConnection connectionToEndpoint = requestHandler.getConnectionToEndpoint();
//                    if (connectionToEndpoint != null && backendsUnreachableOnStuckRequests) {
//                        backendHealthManager.reportBackendUnreachable(
//                                connectionToEndpoint.getKey().getHostPort(), now,
//                                "a request to " + requestHandler.getUri() + " for user " + requestHandler.getUserId() + " appears stuck"
//                        );
//                    }
//                    STUCK_REQUESTS_COUNTER.inc();
//                    toRemove.add(entry.getValue());
//                });
//            }
//            toRemove.forEach(r -> {
//                unregisterPendingRequest(r);
//            });
//        }
//    }
}
