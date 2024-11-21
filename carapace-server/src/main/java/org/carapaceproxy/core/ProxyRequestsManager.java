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
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import jdk.net.ExtendedSocketOptions;
import org.apache.http.HttpStatus;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

/**
 * Manager forwarding {@link ProxyRequest} from clients to proper endpoints.
 *
 * @author paolo.venturi
 */
public class ProxyRequestsManager {

    public static final Gauge PENDING_REQUESTS_GAUGE = PrometheusUtils.createGauge(
            "backends", "pending_requests", "pending requests"
    ).register();

    public static final Counter STUCK_REQUESTS_COUNTER = PrometheusUtils.createCounter(
            "backends", "stuck_requests_total", "stuck requests, this requests will be killed"
    ).register();

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRequestsManager.class);

    private final HttpProxyServer parent;
    private final Map<EndpointKey, EndpointStats> endpointsStats = new ConcurrentHashMap<>();
    private final Map<ConnectionKey, HttpClient> forwardersPool = new ConcurrentHashMap<>(); // endpoint_connectionpool -> forwarder to use
    private final ConnectionsManager connectionsManager = new ConnectionsManager();

    public ProxyRequestsManager(HttpProxyServer parent) {
        this.parent = parent;
    }

    public EndpointStats getEndpointStats(EndpointKey key) {
        return endpointsStats.get(key);
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
        connectionsManager.reloadConfiguration(newConfiguration, newEndpoints);
        forwardersPool.clear();
    }

    public void close() {
        connectionsManager.close();
    }

    /**
     * Process a request received by the HttpServer of a {@link NetworkListenerConfiguration}.
     *
     * @param request the request of a to-be-proxied resource
     * @return a publisher that models the non-blocking request handling result
     */
    public Publisher<Void> processRequest(ProxyRequest request) {
        request.setStartTs(System.currentTimeMillis());
        request.setLastActivity(request.getStartTs());

        parent.getFilters().forEach(filter -> filter.apply(request));

        MapResult action = parent.getMapper().map(request);

        request.setAction(action);

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("{} Mapped {} to {}, userid {}", this, request.getUri(), action, request.getUserId());
        }

        try {
            return switch (action.getAction()) {
                case NOTFOUND -> serveNotFoundMessage(request);
                case INTERNAL_ERROR -> serveInternalErrorMessage(request);
                case SERVICE_UNAVAILABLE -> serveServiceUnavailable(request);
                case MAINTENANCE_MODE -> serveMaintenanceMessage(request);
                case BAD_REQUEST -> serveBadRequestMessage(request);
                case STATIC, ACME_CHALLENGE -> serveStaticMessage(request);
                case REDIRECT -> serveRedirect(request);
                case PROXY -> forward(request, false);
                case CACHE -> serveFromCache(request); // cached content
                default -> throw new IllegalStateException("Action " + action.getAction() + " not supported");
            };
        } finally {
            parent.getRequestsLogger().logRequest(request);
        }
    }

    private Publisher<Void> serveNotFoundMessage(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        SimpleHTTPResponse res = parent.getMapper().mapPageNotFound(request.getAction().getRouteId());
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.errorCode();
            resource = res.resource();
            customHeaders = res.customHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_NOT_FOUND;
        }
        if (code <= 0) {
            code = HttpStatus.SC_NOT_FOUND;
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(code, resource, request.getHttpProtocol());
        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveInternalErrorMessage(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        SimpleHTTPResponse res = parent.getMapper().mapInternalError(request.getAction().getRouteId());
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.errorCode();
            resource = res.resource();
            customHeaders = res.customHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
        }
        if (code <= 0) {
            code = HttpStatus.SC_INTERNAL_SERVER_ERROR;
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(code, resource, request.getHttpProtocol());
        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveMaintenanceMessage(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        SimpleHTTPResponse res = parent.getMapper().mapMaintenanceMode(request.getAction().getRouteId());
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.errorCode();
            resource = res.resource();
            customHeaders = res.customHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_MAINTENANCE_MODE_ERROR;
        }
        if (code <= 0) {
            code = HttpStatus.SC_INTERNAL_SERVER_ERROR;
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(code, resource, request.getHttpProtocol());
        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveBadRequestMessage(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        SimpleHTTPResponse res = parent.getMapper().mapBadRequest();
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.errorCode();
            resource = res.resource();
            customHeaders = res.customHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_BAD_REQUEST;
        }
        if (code <= 0) {
            code = HttpStatus.SC_BAD_REQUEST;
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(code, resource, request.getHttpProtocol());
        return writeSimpleResponse(request, response, customHeaders);
    }

    private Publisher<Void> serveStaticMessage(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(request.getAction().getErrorCode(), request.getAction().getResource(), request.getHttpProtocol());
        return writeSimpleResponse(request, response, request.getAction().getCustomHeaders());
    }

    private Publisher<Void> serveRedirect(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        MapResult action = request.getAction();
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                request.getHttpProtocol(),
                // redirect: 3XX
                HttpResponseStatus.valueOf(action.getErrorCode() < 0 ? HttpStatus.SC_MOVED_TEMPORARILY : action.getErrorCode())
        );
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

        String location = action.getRedirectLocation();
        String host = request.getRequestHostname();
        String port = host.contains(":") ? host.replaceFirst(".*:", ":") : "";
        host = host.split(":")[0];
        String path = request.getUri();
        if (location == null || location.isEmpty()) {
            if (!action.getHost().isEmpty()) {
                host = action.getHost();
            }
            if (action.getPort() > 0) {
                port = ":" + action.getPort();
            } else if (REDIRECT_PROTO_HTTPS.equals(action.getRedirectProto())) {
                port = ""; // default https port
            }
            if (!action.getRedirectPath().isEmpty()) {
                path = action.getRedirectPath();
            }
            location = host + port + path; // - custom redirection
        } else if (location.startsWith("/")) {
            location = host + port + location; // - relative redirection
        } // else: implicit absolute redirection

        // - redirect to https
        location = (REDIRECT_PROTO_HTTPS.equals(action.getRedirectProto()) ? REDIRECT_PROTO_HTTPS : REDIRECT_PROTO_HTTP)
                + "://" + location.replaceFirst("http.?://", "");
        response.headers().set(HttpHeaderNames.LOCATION, location);

        return writeSimpleResponse(request, response, request.getAction().getCustomHeaders());
    }

    private static Publisher<Void> writeSimpleResponse(ProxyRequest request, FullHttpResponse response, List<CustomHeader> customHeaders) {
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
        request.setResponseHeaders(response.headers().copy());
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

    /**
     * Forward a requested received by the {@link Listeners} to the corresponding backend endpoint.
     *
     * @param request the unpacked incoming request to forward to the corresponding backend endpoint
     * @param cache whether the request is cacheable or not
     * @return a {@link Flux} forwarding the returned {@link Publisher} sequence
     */
    public Publisher<Void> forward(ProxyRequest request, boolean cache) {
        final String endpointHost = request.getAction().getHost();
        final int endpointPort = request.getAction().getPort();
        EndpointKey key = EndpointKey.make(endpointHost, endpointPort);
        EndpointStats endpointStats = endpointsStats.computeIfAbsent(key, EndpointStats::new);

        var connectionToEndpoint = connectionsManager.apply(request);
        ConnectionPoolConfiguration connectionConfig = connectionToEndpoint.getKey();
        ConnectionProvider connectionProvider = connectionToEndpoint.getValue();
        if (LOGGER.isDebugEnabled()) {
            Map<String, HttpProxyServer.ConnectionPoolStats> stats = parent.getConnectionPoolsStats().get(key);
            if (stats != null) {
                LOGGER.debug("Connection {} stats: {}", connectionConfig.getId(), stats.get(connectionConfig.getId()));
            }
            LOGGER.debug("Max connections for {}: {}", connectionConfig.getId(), connectionProvider.maxConnectionsPerHost());
        }

        final var protocol = HttpUtils.toHttpProtocol(request.getHttpProtocol(), request.isSecure());
        final var clientKey = new ConnectionKey(key, connectionConfig.getId(), protocol);
        HttpClient forwarder = forwardersPool.computeIfAbsent(clientKey, hostname -> HttpClient.create(connectionProvider)
                .host(endpointHost)
                .port(endpointPort)
                .protocol(protocol)
                .followRedirect(false) // client has to request the redirect, not the proxy
                .runOn(parent.getEventLoopGroup())
                .compress(parent.getCurrentConfiguration().isRequestCompressionEnabled())
                .responseTimeout(Duration.ofMillis(connectionConfig.getStuckRequestTimeout()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionConfig.getConnectTimeout())
                // Enables TCP keepalive: TCP starts sending keepalive probes when a connection is idle for some time.
                .option(ChannelOption.SO_KEEPALIVE, connectionConfig.isKeepAlive())
                .option(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPIDLE
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), connectionConfig.getKeepaliveIdle())
                .option(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPINTVL
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), connectionConfig.getKeepaliveInterval())
                .option(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPCNT
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), connectionConfig.getKeepaliveCount())
                .httpResponseDecoder(option -> option.maxHeaderSize(parent.getCurrentConfiguration().getMaxHeaderSize()))
                .doOnRequest((req, conn) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Start sending request for  Using client id {}_{} Uri {} Timestamp {} Backend {}:{}", key, connectionConfig.getId(), req.resourceUrl(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), endpointHost, endpointPort);
                    }
                    endpointStats.getTotalRequests().incrementAndGet();
                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                }).doAfterRequest((req, conn) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Finished sending request for  Using client id {}_{} Uri {} Timestamp {} Backend {}:{}", key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), endpointHost, endpointPort);
                    }
                }).doAfterResponseSuccess((resp, conn) -> {
                    PENDING_REQUESTS_GAUGE.dec();
                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                }));

        AtomicBoolean cacheable = new AtomicBoolean(cache);
        final ContentsCache.ContentReceiver cacheReceiver = cacheable.get() ? parent.getCache().createCacheReceiver(request) : null;
        if (cacheReceiver != null) { // cacheable
            // https://tools.ietf.org/html/rfc7234#section-4.3.4
            cleanRequestFromCacheValidators(request);
        } else {
            cacheable.set(false);
        }

        PENDING_REQUESTS_GAUGE.inc();
        return forwarder.request(request.getMethod())
                .uri(request.getUri())
                .send((req, out) -> {
                    // client request headers
                    req.headers(request.getRequestHeaders().copy());
                    // netty overrides the value, we need to force it
                    req.header(HttpHeaderNames.HOST, request.getRequestHeaders().get(HttpHeaderNames.HOST));
                    return out.send(request.getRequestData()); // client request body
                })
                .response((resp, flux) -> { // endpoint response
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Receive response from backend for {} Using client id {}_{} uri{} timestamp {} Backend: {}", request.getRemoteAddress(), key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), request.getAction().getHost());
                    }

                    request.setResponseStatus(resp.status());
                    request.setResponseHeaders(resp.responseHeaders().copy()); // headers from endpoint to client
                    if (cacheable.get() && parent.getCache().isCacheable(resp) && cacheReceiver.receivedFromRemote(resp)) {
                        addCachedResponseHeaders(request);
                    } else {
                        cacheable.set(false);
                    }
                    addCustomResponseHeaders(request, request.getAction().getCustomHeaders());

                    if (aggregateChunksForLegacyHttp(request)) {
                        return request.sendResponseData(flux.aggregate().retain().map(ByteBuf::asByteBuf)
                                .doOnNext(data -> {
                                    request.setLastActivity(System.currentTimeMillis());
                                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                                    if (cacheable.get()) {
                                        cacheReceiver.receivedFromRemote(data, parent.getCachePoolAllocator());
                                    }
                                }).doOnSuccess(data -> {
                                    if (cacheable.get()) {
                                        parent.getCache().cacheContent(cacheReceiver);
                                    }
                                }));
                    }

                    return request.sendResponseData(flux.retain().doOnNext(data -> { // response data
                        request.setLastActivity(System.currentTimeMillis());
                        endpointStats.getLastActivity().set(System.currentTimeMillis());
                        if (cacheable.get()) {
                            cacheReceiver.receivedFromRemote(data, parent.getCachePoolAllocator());
                        }
                    }).doOnComplete(() -> {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Send all response to client {} Using client id {}_{} for uri {} timestamp {} Backend: {}", request.getRemoteAddress(), key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), request.getAction().getHost());
                        }
                        if (cacheable.get()) {
                            parent.getCache().cacheContent(cacheReceiver);
                        }
                    }));
                }).onErrorResume(err -> { // custom endpoint request/response error handling
                    PENDING_REQUESTS_GAUGE.dec();

                    EndpointKey endpoint = EndpointKey.make(request.getAction().getHost(), request.getAction().getPort());
                    if (err instanceof ReadTimeoutException) {
                        STUCK_REQUESTS_COUNTER.inc();
                        LOGGER.error("Read timeout error occurred for endpoint {}; request: {}", endpoint, request);
                        if (parent.getCurrentConfiguration().isBackendsUnreachableOnStuckRequests()) {
                            parent.getBackendHealthManager().reportBackendUnreachable(
                                    endpoint, System.currentTimeMillis(), "Error: " + err
                            );
                        }
                        return serveInternalErrorMessage(request);
                    }

                    LOGGER.error("Error proxying request for endpoint {}; request: {}", endpoint, request, err);
                    if (err instanceof ConnectException) {
                        parent.getBackendHealthManager().reportBackendUnreachable(
                                endpoint, System.currentTimeMillis(), "Error: " + err
                        );
                    }
                    return serveServiceUnavailable(request);
                });
    }

    private boolean aggregateChunksForLegacyHttp(ProxyRequest request) {
        return parent.getCurrentConfiguration().isHttp10BackwardCompatibilityEnabled()
                && request.getRequest().version() == HttpVersion.HTTP_1_0;
    }

    private Publisher<Void> serveServiceUnavailable(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        SimpleHTTPResponse res = parent.getMapper().mapServiceUnavailableError(request.getAction().getRouteId());
        int code = 0;
        String resource = null;
        List<CustomHeader> customHeaders = null;
        if (res != null) {
            code = res.errorCode();
            resource = res.resource();
            customHeaders = res.customHeaders();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_SERVICE_UNAVAILABLE_ERROR;
        }
        if (code <= 0) {
            code = HttpStatus.SC_SERVICE_UNAVAILABLE;
        }
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(code, resource, request.getHttpProtocol());
        return writeSimpleResponse(request, response, customHeaders);
    }

    private static void cleanRequestFromCacheValidators(ProxyRequest request) {
        HttpHeaders headers = request.getRequestHeaders();
        headers.remove(HttpHeaderNames.IF_MATCH);
        headers.remove(HttpHeaderNames.IF_MODIFIED_SINCE);
        headers.remove(HttpHeaderNames.IF_NONE_MATCH);
        headers.remove(HttpHeaderNames.IF_RANGE);
        headers.remove(HttpHeaderNames.IF_UNMODIFIED_SINCE);
        headers.remove(HttpHeaderNames.ETAG);
        headers.remove(HttpHeaderNames.CONNECTION);
    }

    private void addCachedResponseHeaders(ProxyRequest request) {
        HttpHeaders headers = request.getResponseHeaders();
        if (!headers.contains(HttpHeaderNames.EXPIRES)) {
            headers.add(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new Date(parent.getCache().computeDefaultExpireDate())));
        }
    }

    private Publisher<Void> serveFromCache(ProxyRequest request) {
        ContentsCache.ContentSender cacheSender = parent.getCache().getCacheSender(request);
        if (cacheSender == null) {
            // content non cached, forwarding and caching...
            return forward(request, true);
        }
        request.setServedFromCache(true);

        ContentsCache.CachedContent content = cacheSender.getCached();
        HttpClientResponse response = content.getResponse();

        // content modified
        if (content.modifiedSince(request)) {
            request.setResponseStatus(response.status());
            HttpHeaders headers = response.responseHeaders().copy();
            headers.remove(HttpHeaderNames.EXPIRES);
            headers.remove(HttpHeaderNames.ACCEPT_RANGES);
            headers.remove(HttpHeaderNames.ETAG);
            headers.add("X-Cached", "yes; ts=" + content.getCreationTs());
            headers.add(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new Date(content.getExpiresTs())));
            request.setResponseHeaders(headers);
            addCustomResponseHeaders(request, request.getAction().getCustomHeaders());
            // If the request is http 1.0, we make sure to send without chunked
            if (aggregateChunksForLegacyHttp(request)) {
                return request.sendResponseData(Mono.from(ByteBufFlux.fromIterable(content.getChunks())));
            }
            // body
            return request.sendResponseData(Flux.fromIterable(content.getChunks()).doOnNext(data -> { // response data
                request.setLastActivity(System.currentTimeMillis());
            }));
        }

        // content not modified
        request.setResponseStatus(HttpResponseStatus.NOT_MODIFIED);
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.LAST_MODIFIED, HttpUtils.formatDateHeader(new Date(content.getLastModified())));
        headers.set(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new Date(content.getExpiresTs())));
        headers.add("X-Cached", "yes; ts=" + content.getCreationTs());
        request.setResponseHeaders(headers);
        return request.send();
    }

    public static class ConnectionsManager implements AutoCloseable, Function<ProxyRequest, Map.Entry<ConnectionPoolConfiguration, ConnectionProvider>> {

        private final Map<ConnectionPoolConfiguration, ConnectionProvider> connectionPools = new ConcurrentHashMap<>();
        private volatile Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> defaultConnectionPool;

        public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
            close();

            // custom pools
            final var connectionPoolsCopy = new ArrayList<>(newConfiguration.getConnectionPools().values());

            // default pool
            connectionPoolsCopy.add(newConfiguration.getDefaultConnectionPool());

            connectionPoolsCopy.forEach(connectionPool -> {
                if (!connectionPool.isEnabled()) {
                    return;
                }

                ConnectionProvider.Builder builder = ConnectionProvider.builder(connectionPool.getId())
                        .disposeTimeout(Duration.ofMillis(connectionPool.getDisposeTimeout()));

                // max connections per endpoint limit setup
                newEndpoints.forEach(be -> {
                    LOGGER.debug("Setup max connections per endpoint {}:{} = {} for connectionpool {}", be.host(), be.port(), connectionPool.getMaxConnectionsPerEndpoint(), connectionPool.getId());
                    builder.forRemoteHost(InetSocketAddress.createUnresolved(be.host(), be.port()), spec -> {
                        spec.maxConnections(connectionPool.getMaxConnectionsPerEndpoint());
                        spec.pendingAcquireTimeout(Duration.ofMillis(connectionPool.getBorrowTimeout()));
                        spec.maxIdleTime(Duration.ofMillis(connectionPool.getIdleTimeout()));
                        spec.maxLifeTime(Duration.ofMillis(connectionPool.getMaxLifeTime()));
                        spec.evictInBackground(Duration.ofMillis(connectionPool.getIdleTimeout() * 2L));
                        spec.metrics(true);
                        spec.lifo();
                    });
                });

                if (connectionPool.getId().equals("*")) {
                    defaultConnectionPool = Map.entry(connectionPool, builder.build());
                } else {
                    connectionPools.put(connectionPool, builder.build());
                }
            });
        }

        @Override
        public void close() {
            connectionPools.values().forEach(ConnectionProvider::dispose); // graceful shutdown according to disposeTimeout
            connectionPools.clear();

            if (defaultConnectionPool != null) {
                defaultConnectionPool.getValue().dispose(); // graceful shutdown according to disposeTimeout
            }

            // reset connections provider metrics
            Metrics.globalRegistry.forEachMeter(m -> {
                if (m.getId().getName().startsWith(CONNECTION_PROVIDER_PREFIX)) {
                    Metrics.globalRegistry.remove(m);
                }
            });
        }

        @Override
        public Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> apply(ProxyRequest request) {
            String hostName = request.getRequestHostname();
            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> selectedPool = connectionPools.entrySet().stream()
                    .filter(e -> Pattern.matches(e.getKey().getDomain(), hostName))
                    .findFirst()
                    .orElse(defaultConnectionPool);

            LOGGER.debug("Using connection {} for domain {}", selectedPool.getKey().getId(), hostName);

            return selectedPool;
        }
    }

    @VisibleForTesting
    public ConnectionsManager getConnectionsManager() {
        return connectionsManager;
    }

    @VisibleForTesting
    public Map<ConnectionPoolConfiguration, ConnectionProvider> getConnectionPools() {
        HashMap<ConnectionPoolConfiguration, ConnectionProvider> pools = new HashMap<>(connectionsManager.connectionPools);
        pools.put(connectionsManager.defaultConnectionPool.getKey(), connectionsManager.defaultConnectionPool.getValue());

        return pools;
    }
}
