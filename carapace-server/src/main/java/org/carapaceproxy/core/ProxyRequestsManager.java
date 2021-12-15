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
import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

/**
 * Manager forwarding {@link ProxyRequest} from clients to proper endpoints.
 *
 * @author paolo.venturi
 */
public class ProxyRequestsManager {

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

    private static final Logger LOGGER = Logger.getLogger(ProxyRequestsManager.class.getName());

    private final HttpProxyServer parent;
    private final ConnectionsManager connectionsManager = new ConnectionsManager();
    private final ConcurrentHashMap<EndpointKey, EndpointStats> endpointsStats = new ConcurrentHashMap<>();

    public ProxyRequestsManager(HttpProxyServer parent) {
        this.parent = parent;
    }

    public EndpointStats getEndpointStats(EndpointKey key) {
        return endpointsStats.get(key);
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
        connectionsManager.reloadConfiguration(newConfiguration, newEndpoints);
    }

    public void close() {
        connectionsManager.close();
    }

    public Publisher<Void> processRequest(ProxyRequest request) {
        request.setStartTs(System.currentTimeMillis());
        request.setLastActivity(request.getStartTs());

        parent.getFilters().forEach(filter -> filter.apply(request));

        MapResult action = parent.getMapper().map(request);
        if (action == null) {
            LOGGER.log(Level.INFO, "Mapper returned NULL action for {0}", this);
            action = MapResult.internalError(MapResult.NO_ROUTE);
        }
        request.setAction(action);
        parent.getRequestsLogger().logRequest(request);

        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "{0} Mapped {1} to {2}, userid {3}", new Object[]{this, request.getUri(), action, request.getUserId()});
        }

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
                RequestForwarder forwarder = new RequestForwarder(request, null);
                return forwarder.forward();
            }

            case CACHE: {
                ContentsCache.ContentSender cacheSender = parent.getCache().getCacheSender(request);
                if (cacheSender != null) {
                    return serveFromCache(request, cacheSender); // cached content
                }
                ContentsCache.ContentReceiver cacheReceiver = parent.getCache().createCacheReceiver(request);
                if (cacheReceiver != null) { // cacheable
                    // https://tools.ietf.org/html/rfc7234#section-4.3.4
                    cleanRequestFromCacheValidators(request);
                }
                RequestForwarder forwarder = new RequestForwarder(request, cacheReceiver);
                return forwarder.forward();
            }

            default:
                throw new IllegalStateException("Action " + action.action + " not supported");
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

    private static Publisher<Void> writeSimpleResponse(ProxyRequest request, FullHttpResponse response) {
        return writeSimpleResponse(request, response, request.getAction().customHeaders);
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

    private final class RequestForwarder {

        private final ProxyRequest request;
        private ContentsCache.ContentReceiver cacheReceiver;
        private final EndpointStats endpointStats;
        private HttpClient client;
        private volatile boolean requestRunning;

        private RequestForwarder(ProxyRequest request, ContentsCache.ContentReceiver cacheReceiver) {
            this.request = request;
            this.cacheReceiver = cacheReceiver;
            final String endpointHost = request.getAction().host;
            final int endpointPort = request.getAction().port;
            endpointStats = endpointsStats.computeIfAbsent(EndpointKey.make(endpointHost, endpointPort), EndpointStats::new);

            Counter.Child requestsPerUser = request.getUserId() != null
                    ? USER_REQUESTS_COUNTER.labels(request.getUserId())
                    : USER_REQUESTS_COUNTER.labels("anonymous");

            Counter.Child totalRequests = TOTAL_REQUESTS_COUNTER.labels(request.getListener() + "");

            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> connectionToEndpoint = connectionsManager.apply(request);
            ConnectionPoolConfiguration connectionConfig = connectionToEndpoint.getKey();
            ConnectionProvider connectionProvider = connectionToEndpoint.getValue();
            if (CarapaceLogger.isLoggingDebugEnabled()) {
                Map<String, HttpProxyServer.ConnectionPoolStats> stats = parent.getConnectionPoolsStats().get(EndpointKey.make(endpointHost, endpointPort));
                if (stats != null) {
                    CarapaceLogger.debug("Connection {0} stats: {1}", connectionConfig.getId(), stats.get(connectionConfig.getId()));
                }
                CarapaceLogger.debug("Max connections for {0}: {1}", connectionConfig.getId(), connectionProvider.maxConnectionsPerHost());
            }

            client = HttpClient.create(connectionProvider)
                    .host(endpointHost)
                    .port(endpointPort)
                    .followRedirect(false) // clients has to request the redirect, not the proxy
                    .compress(parent.getCurrentConfiguration().isRequestCompressionEnabled())
                    .responseTimeout(Duration.ofMillis(connectionConfig.getStuckRequestTimeout()))
                    .option(ChannelOption.SO_KEEPALIVE, true) // Enables TCP keepalive: TCP starts sending keepalive probes when a connection is idle for some time.
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionConfig.getConnectTimeout())
                    .headers(h -> h.add(request.getRequestHeaders().copy()))
                    .doOnRequest((req, conn) -> {
                        PENDING_REQUESTS_GAUGE.inc();
                        requestRunning = true;
                        requestsPerUser.inc();
                        totalRequests.inc();
                        endpointStats.getTotalRequests().incrementAndGet();
                        endpointStats.getLastActivity().set(System.currentTimeMillis());
                    })
                    .doAfterResponseSuccess((resp, conn) -> {
                        if (requestRunning) {
                            requestRunning = false;
                            PENDING_REQUESTS_GAUGE.dec();
                        }
                        endpointStats.getLastActivity().set(System.currentTimeMillis());
                    });

            request.getRequestCookies().forEach(cookie -> client = client.cookie(cookie));
        }

        public Publisher<Void> forward() {
            return client.request(request.getMethod())
                    .uri(request.getUri())
                    .send(request.getRequestData()) // client request body
                    .response((resp, flux) -> { // endpoint response
                        request.setResponseStatus(resp.status());
                        request.setResponseHeaders(resp.responseHeaders().copy()); // headers from endpoint to client
                        if (cacheReceiver != null && parent.getCache().isCacheable(resp) && cacheReceiver.receivedFromRemote(resp)) {
                            addCachedResponseHeaders(request);
                        } else {
                            cacheReceiver = null;
                        }
                        addCustomResponseHeaders(request, request.getAction().customHeaders);
                        request.setResponseCookies(resp.cookies().values().stream() // cookies from endpoint to client
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList())
                        );
                        return request.sendResponseData(flux.retain().doOnNext(data -> { // response data
                            request.setLastActivity(System.currentTimeMillis());
                            endpointStats.getLastActivity().set(System.currentTimeMillis());
                            if (cacheReceiver != null) {
                                cacheReceiver.receivedFromRemote(data);
                            }
                        }).doOnComplete(() -> parent.getCache().cacheContent(cacheReceiver)));
                    }).onErrorResume(err -> { // custom endpoint request/response error handling
                if (requestRunning) {
                    requestRunning = false;
                    PENDING_REQUESTS_GAUGE.dec();
                }

                String endpoint = request.getAction().host + ":" + request.getAction().port;
                if (err instanceof io.netty.handler.timeout.ReadTimeoutException) {
                    STUCK_REQUESTS_COUNTER.inc();
                    LOGGER.log(Level.SEVERE, "Read timeout error occurred for endpoint {0}; request: {1}", new Object[]{endpoint, request});
                    if (parent.getCurrentConfiguration().isBackendsUnreachableOnStuckRequests()) {
                        parent.getBackendHealthManager().reportBackendUnreachable(
                                endpoint, System.currentTimeMillis(), "Error: " + err
                        );
                    }
                    return serveInternalErrorMessage(request);
                }

                LOGGER.log(Level.SEVERE, "Error proxying request for endpoint {0}; request: {1};\nError: {2}", new Object[]{endpoint, request, err});
                if (err instanceof ConnectException) {
                    parent.getBackendHealthManager().reportBackendUnreachable(
                            endpoint, System.currentTimeMillis(), "Error: " + err
                    );
                }
                return serveServiceNotAvailable(request);
            });
        }
    }

    private Publisher<Void> serveServiceNotAvailable(ProxyRequest request) {
        FullHttpResponse response = parent.getStaticContentsManager().buildServiceNotAvailableResponse();
        return writeSimpleResponse(request, response);
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
            headers.add(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new java.util.Date(parent.getCache().computeDefaultExpireDate())));
        }
    }

    private Publisher<Void> serveFromCache(ProxyRequest request, ContentsCache.ContentSender cacheSender) {
        ContentsCache.CachedContent content = cacheSender.getCached();
        HttpClientResponse response = content.getResponse();

        // content not modified
        long ifModifiedSince = request.getRequestHeaders().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, -1);
        if (ifModifiedSince != -1 && content.getLastModified() > 0 && ifModifiedSince >= content.getLastModified()) {
            request.setResponseStatus(HttpResponseStatus.NOT_MODIFIED);
            HttpHeaders headers = new DefaultHttpHeaders();
            headers.set(HttpHeaderNames.LAST_MODIFIED, HttpUtils.formatDateHeader(new java.util.Date(content.getLastModified())));
            headers.set(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new java.util.Date(content.getExpiresTs())));
            headers.add("X-Cached", "yes; ts=" + content.getCreationTs());
            request.setResponseHeaders(headers);
            return request.send();
        } else { // content modified
            request.setResponseStatus(response.status());
            HttpHeaders headers = response.responseHeaders().copy();
            headers.remove(HttpHeaderNames.EXPIRES);
            headers.remove(HttpHeaderNames.ACCEPT_RANGES);
            headers.remove(HttpHeaderNames.ETAG);
            headers.add("X-Cached", "yes; ts=" + content.getCreationTs());
            headers.add(HttpHeaderNames.EXPIRES, HttpUtils.formatDateHeader(new java.util.Date(content.getExpiresTs())));
            request.setResponseHeaders(headers);
            addCustomResponseHeaders(request, request.getAction().customHeaders);
            // cookies
            request.setResponseCookies(response.cookies().values().stream() // cached cookies from endpoint to client
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList())
            );
            // body
            return request.sendResponseData(Flux.fromIterable(content.getChunks()).doOnNext(data -> { // response data
                request.setLastActivity(System.currentTimeMillis());
            }));
        }
    }

    public class ConnectionsManager implements AutoCloseable, Function<ProxyRequest, Map.Entry<ConnectionPoolConfiguration, ConnectionProvider>> {

        private final Map<ConnectionPoolConfiguration, ConnectionProvider> connectionPools = new ConcurrentHashMap<>();
        private volatile Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> defaultConnectionPool;

        public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
            close();

            // custom pools
            ArrayList<ConnectionPoolConfiguration> _connectionPools = new ArrayList<>(newConfiguration.getConnectionPools());

            // default pool
            _connectionPools.add(newConfiguration.getDefaultConnectionPool());

            _connectionPools.forEach(connectionPool -> {
                if (!connectionPool.isEnabled()) {
                    return;
                }

                ConnectionProvider.Builder builder = ConnectionProvider.builder(connectionPool.getId())
                        .disposeTimeout(Duration.ofMillis(connectionPool.getDisposeTimeout()));

                // max connections per endpoint limit setup
                newEndpoints.forEach(be -> {
                    CarapaceLogger.debug("Setup max connections per endpoint {0}:{1} = {2} for connectionpool {3}",
                            be.getHost(), be.getPort() + "", connectionPool.getMaxConnectionsPerEndpoint(), connectionPool.getId()
                    );
                    builder.forRemoteHost(InetSocketAddress.createUnresolved(be.getHost(), be.getPort()), spec -> {
                        spec.maxConnections(connectionPool.getMaxConnectionsPerEndpoint());
                        spec.pendingAcquireTimeout(Duration.ofMillis(connectionPool.getBorrowTimeout()));
                        spec.maxIdleTime(Duration.ofMillis(connectionPool.getIdleTimeout()));
                        spec.evictInBackground(Duration.ofMillis(connectionPool.getIdleTimeout() * 2));
                        spec.lifo();
                        spec.metrics(true);
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
            connectionPools.values().forEach(connectionProvider -> {
                connectionProvider.dispose(); // graceful shutdown according to disposeTimeout
            });
            connectionPools.clear();

            if (defaultConnectionPool != null) {
                defaultConnectionPool.getValue().dispose(); // graceful shutdown according to disposeTimeout
            }
        }

        @Override
        public Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> apply(ProxyRequest request) {
            String hostName = request.getRequestHostname();
            Map.Entry<ConnectionPoolConfiguration, ConnectionProvider> selectedPool = connectionPools.entrySet().stream()
                    .filter(e -> Pattern.matches(e.getKey().getDomain(), hostName))
                    .findFirst()
                    .orElse(defaultConnectionPool);

            CarapaceLogger.debug("Using connection {0} for domain {1}", selectedPool.getKey().getId(), hostName);

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
