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
import static org.carapaceproxy.utils.AlpnUtils.configureAlpnForClient;
import com.google.common.annotations.VisibleForTesting;
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
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.io.File;
import java.net.ConnectException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import jdk.net.ExtendedSocketOptions;
import org.apache.http.HttpStatus;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.carapaceproxy.utils.StringUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpProtocol;
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
    public final ConnectionsManager connectionsManager = new ConnectionsManager();

    public ProxyRequestsManager(HttpProxyServer parent) {
        this.parent = parent;
    }

    public EndpointStats getEndpointStats(final EndpointKey key) {
        return endpointsStats.get(key);
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
        connectionsManager.reloadConfiguration(newConfiguration, newEndpoints);
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
                case PROXY -> forward(request, false, action.getHealthStatus());
                case CACHE -> serveFromCache(request, action.getHealthStatus()); // cached content
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

        final MapResult action = Objects.requireNonNull(request.getAction());
        SimpleHTTPResponse res = parent.getMapper().mapPageNotFound(action.getRouteId());
        int code = res.errorCode();
        String resource = res.resource();
        List<CustomHeader> customHeaders = res.customHeaders();
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

        final MapResult action = Objects.requireNonNull(request.getAction());
        SimpleHTTPResponse res = parent.getMapper().mapInternalError(action.getRouteId());
        int code = res.errorCode();
        String resource = res.resource();
        List<CustomHeader> customHeaders = res.customHeaders();
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

        final MapResult action = Objects.requireNonNull(request.getAction());
        SimpleHTTPResponse res = parent.getMapper().mapMaintenanceMode(action.getRouteId());
        int code = res.errorCode();
        String resource = res.resource();
        List<CustomHeader> customHeaders = res.customHeaders();
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
        int code = res.errorCode();
        String resource = res.resource();
        List<CustomHeader> customHeaders = res.customHeaders();
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
        final MapResult action = Objects.requireNonNull(request.getAction());
        FullHttpResponse response = parent
                .getStaticContentsManager()
                .buildResponse(action.getErrorCode(), action.getResource(), request.getHttpProtocol());
        return writeSimpleResponse(request, response, action.getCustomHeaders());
    }

    private Publisher<Void> serveRedirect(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        MapResult action = Objects.requireNonNull(request.getAction());
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                request.getHttpProtocol(),
                // redirect: 3XX
                HttpResponseStatus.valueOf(action.getErrorCode() < 0 ? HttpStatus.SC_MOVED_TEMPORARILY : action.getErrorCode())
        );
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

        String location = action.getRedirectLocation();
        final String hostname = request.getRequestHostname();
        String host = null;
        String port = null;
        if (hostname != null) {
            host = hostname.split(":")[0];
            port = hostname.contains(":") ? hostname.replaceFirst(".*:", ":") : "";
        }
        String path = request.getUri();
        if (location == null || location.isEmpty()) {
            if (!StringUtils.isBlank(action.getHost())) {
                host = action.getHost();
            }
            if (action.getPort() > 0) {
                port = ":" + action.getPort();
            } else if (REDIRECT_PROTO_HTTPS.equals(action.getRedirectProto())) {
                port = ""; // default https port
            }
            if (!StringUtils.isBlank(action.getRedirectPath())) {
                path = action.getRedirectPath();
            }
            location = Objects.requireNonNull(host) + Objects.requireNonNull(port) + path; // - custom redirection
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
        addCustomResponseHeaders(request.getResponseHeaders(), customHeaders);

        // Write the response
        return request.sendResponseData(Mono.just(response.content()).doFinally(f -> request.setLastActivity(System.currentTimeMillis())));
    }

    private static void applyCustomResponseHeaders(final ProxyRequest request) {
        final MapResult action = Objects.requireNonNull(request.getAction());
        addCustomResponseHeaders(request.getResponseHeaders(), action.getCustomHeaders());
    }

    private static void addCustomResponseHeaders(final HttpHeaders responseHeaders, final List<CustomHeader> customHeaders) {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return;
        }
        customHeaders.forEach(customHeader -> {
            if (CustomHeader.HeaderMode.SET.equals(customHeader.getMode())
                    || CustomHeader.HeaderMode.REMOVE.equals(customHeader.getMode())) {
                responseHeaders.remove(customHeader.getName());
            }
            if (CustomHeader.HeaderMode.SET.equals(customHeader.getMode())
                    || CustomHeader.HeaderMode.ADD.equals(customHeader.getMode())) {
                responseHeaders.add(customHeader.getName(), customHeader.getValue());
            }
        });
    }

    /**
     * Forward a requested received by the {@link Listeners} to the corresponding backend endpoint.
     *
     * @param request      the unpacked incoming request to forward to the corresponding backend endpoint
     * @param cache        whether the request is cacheable or not
     * @param healthStatus the health status of the chosen backend; it should be notified when connection starts and ends
     * @return a {@link Flux} forwarding the returned {@link Publisher} sequence
     */
    public Publisher<Void> forward(final ProxyRequest request, final boolean cache, final BackendHealthStatus healthStatus) {
        Objects.requireNonNull(request.getAction());
        final EndpointKey key = EndpointKey.make(request.getAction().getHost(), request.getAction().getPort());
        final EndpointStats endpointStats = endpointsStats.computeIfAbsent(key, EndpointStats::new);

        final String hostName = request.getRequestHostname();
        final ConnectionPoolConfiguration connectionConfig = connectionsManager.findConnectionPool(hostName);
        final ConnectionProvider connectionProvider = connectionsManager.getConnectionProvider(connectionConfig);
        LOGGER.debug("Using connection {} for domain {}", connectionConfig.getId(), hostName);
        if (LOGGER.isDebugEnabled()) {
            final Map<String, HttpProxyServer.ConnectionPoolStats> stats = parent.getConnectionPoolsStats().get(key);
            if (stats != null) {
                LOGGER.debug(
                        "Connection {} stats: {}",
                        connectionConfig.getId(),
                        stats.get(connectionConfig.getId())
                );
            }
            LOGGER.debug(
                    "Max connections for {}: {}",
                    connectionConfig.getId(),
                    connectionProvider.maxConnectionsPerHost()
            );
        }

        BackendConfiguration backendConfig = getBackendConfiguration(request);
        String caCertificatePath = backendConfig != null ? backendConfig.caCertificatePath() : null;
        String caCertificatePassword = backendConfig != null ? backendConfig.caCertificatePassword() : null;
        HttpClient forwarder = getClient(connectionProvider, key, request.getAction().isSsl(), caCertificatePath, caCertificatePassword)
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
                        LOGGER.debug(
                                "Start sending request for  Using client id {}_{} Uri {} Timestamp {} Backend {}:{}", key, connectionConfig.getId(), req.resourceUrl(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), key.host(),
                                key.port()
                        );
                    }
                    endpointStats.getTotalRequests().incrementAndGet();
                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                }).doAfterRequest((req, conn) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Finished sending request for  Using client id {}_{} Uri {} Timestamp {} Backend {}:{}", key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")), key.host(),
                                key.port()
                        );
                    }
                }).doAfterResponseSuccess((resp, conn) -> {
                    PENDING_REQUESTS_GAUGE.dec();
                    healthStatus.decrementConnections();
                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                });

        AtomicBoolean cacheable = new AtomicBoolean(cache);
        final ContentsCache.ContentReceiver cacheReceiver = cacheable.get() ? parent.getCache().createCacheReceiver(request) : null;
        if (cacheReceiver != null) { // cacheable
            // https://tools.ietf.org/html/rfc7234#section-4.3.4
            cleanRequestFromCacheValidators(request);
        } else {
            cacheable.set(false);
        }

        PENDING_REQUESTS_GAUGE.inc();
        healthStatus.incrementConnections();
        return forwarder.request(request.getMethod())
                .uri(request.getUri())
                .send((req, out) -> {
                    // we don't want to upgrade to HTTP/2 if Carapace supports it, but the backend doesn't
                    final HttpHeaders copy = request.getRequestHeaders().copy();
                    if (copy.contains(HttpHeaderNames.UPGRADE)) {
                        final List<String> upgrade = copy.getAll(HttpHeaderNames.UPGRADE);
                        if (upgrade.contains("HTTP/2")) {
                            // we drop connection and upgrade only if the target upgrade is HTTP/2.0;
                            // else, we want to preserve upgrades to HTTPS or similar
                            final List<String> connection = copy.getAll(HttpHeaderNames.CONNECTION);
                            connection.removeIf("upgrade"::equalsIgnoreCase);
                            copy.remove(HttpHeaderNames.CONNECTION);
                            copy.add(HttpHeaderNames.CONNECTION, connection);

                            upgrade.remove("HTTP/2");
                            copy.remove(HttpHeaderNames.UPGRADE);
                            copy.add(HttpHeaderNames.UPGRADE, upgrade);
                        }
                    }
                    copy.remove(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER);
                    req.headers(copy);
                    // netty overrides the value, we need to force it
                    req.header(HttpHeaderNames.HOST, request.getRequestHostname());
                    return out.send(request.getRequestData()); // client request body
                })
                .response((resp, flux) -> { // endpoint response
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Receive response from backend for {} Using client id {}_{} uri{} timestamp {} Backend: {}", request.getRemoteAddress(), key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")),
                                request.getAction().getHost()
                        );
                    }

                    request.setResponseStatus(resp.status());
                    request.setResponseHeaders(resp.responseHeaders().copy()); // headers from endpoint to client
                    if (cacheable.get() && parent.getCache().isCacheable(resp) && Objects.requireNonNull(cacheReceiver).receivedFromRemote(resp)) {
                        addCachedResponseHeaders(request);
                    } else {
                        cacheable.set(false);
                    }
                    applyCustomResponseHeaders(request);

                    if (aggregateChunksForLegacyHttp(request)) {
                        return request.sendResponseData(flux.aggregate().retain().map(ByteBuf::asByteBuf)
                                .doOnNext(data -> {
                                    request.setLastActivity(System.currentTimeMillis());
                                    endpointStats.getLastActivity().set(System.currentTimeMillis());
                                    if (cacheable.get()) {
                                        Objects.requireNonNull(cacheReceiver).receivedFromRemote(data, parent.getCachePoolAllocator());
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
                            Objects.requireNonNull(cacheReceiver).receivedFromRemote(data, parent.getCachePoolAllocator());
                        }
                    }).doOnComplete(() -> {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Send all response to client {} Using client id {}_{} for uri {} timestamp {} Backend: {}", request.getRemoteAddress(), key, connectionConfig.getId(), request.getUri(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")),
                                    request.getAction().getHost()
                            );
                        }
                        if (cacheable.get()) {
                            parent.getCache().cacheContent(cacheReceiver);
                        }
                    }));
                }).onErrorResume(err -> { // custom endpoint request/response error handling
                    PENDING_REQUESTS_GAUGE.dec();
                    healthStatus.decrementConnections();

                    final EndpointKey endpoint = EndpointKey.make(request.getAction().getHost(), request.getAction().getPort());
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

    /**
     * Creates an HTTP client for connecting to a backend endpoint.
     * <p>
     * This method ensures that the same HTTP protocol version is used for both client-to-proxy
     * and proxy-to-backend communications, while ensuring compatibility with the SSL settings.
     * For example, HTTP/2 over TLS (H2) is used for HTTPS connections, while HTTP/2 cleartext (H2C)
     * is used for HTTP connections.
     *
     * @param connectionProvider    the connection provider to use
     * @param endpoint              the endpoint to connect to
     * @param secure                whether to use SSL/TLS for the connection
     * @param caCertificatePath     path to the CA certificate (may be null)
     * @param caCertificatePassword password for the CA certificate (may be null)
     * @return a configured HttpClient
     */
    private HttpClient getClient(final ConnectionProvider connectionProvider, final EndpointKey endpoint,
                                 final boolean secure,
                            final String caCertificatePath, final String caCertificatePassword) {
        try {
            final HttpClient httpClient = HttpClient.create(connectionProvider)
                    .host(endpoint.host())
                    .port(endpoint.port())
                    .protocol(secure ? HttpProtocol.H2 : HttpProtocol.H2C, HttpProtocol.HTTP11);

            if (secure) {
                final SslContextBuilder sslContextBuilder =
                        createSslContextBuilder(endpoint, caCertificatePath, caCertificatePassword);
                return buildSecureHttpClient(httpClient, sslContextBuilder);
            }
            return httpClient;
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates an appropriate SslContextBuilder based on the provided parameters.
     *
     * @param endpoint              the endpoint key containing host and port
     * @param caCertificatePath     path to the CA certificate (may be null)
     * @param caCertificatePassword password for the CA certificate (may be null)
     * @return an SslContextBuilder configured with the appropriate settings, or null if configuration failed
     */
    private SslContextBuilder createSslContextBuilder(final EndpointKey endpoint, final String caCertificatePath, final String caCertificatePassword) {
        if (!StringUtils.isBlank(caCertificatePath)) {
            try {
                final String pwd = caCertificatePassword != null ? caCertificatePassword : "";
                final File basePath = parent.getBasePath();
                final KeyStore trustStore = CertificatesUtils.loadKeyStoreFromFile(caCertificatePath, pwd, basePath);
                if (trustStore != null) {
                    final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trustManagerFactory.init(trustStore);
                    final SslContextBuilder builder = SslContextBuilder.forClient().trustManager(trustManagerFactory);
                    configureAlpnForClient(endpoint, builder);
                    return builder;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load CA certificate from {} : {}", caCertificatePath, e.getMessage());
                // Fall back to default SslContextBuilder
            }
        }
        final SslContextBuilder builder = SslContextBuilder.forClient();
        configureAlpnForClient(endpoint, builder);
        return builder;
    }

    /**
     * Builds a secure HttpClient using the provided SslContextBuilder.
     *
     * @param httpClient the base HttpClient to configure
     * @param sslContextBuilder the SslContextBuilder to use
     * @return a configured secure HttpClient, or null if building the SslContext failed
     */
    private HttpClient buildSecureHttpClient(final HttpClient httpClient, final SslContextBuilder sslContextBuilder) throws SSLException {
        final SslContext sslContext = sslContextBuilder.build();
        return httpClient.secure(spec -> spec.sslContext(sslContext));
    }

    private BackendConfiguration getBackendConfiguration(ProxyRequest request) {
        if (request.getAction() == null) {
            return null;
        }

        String host = request.getAction().getHost();
        int port = request.getAction().getPort();
        EndpointKey key = EndpointKey.make(host, port);

        // Find the backend configuration that matches this endpoint
        for (BackendConfiguration backend : parent.getMapper().getBackends().values()) {
            if (backend.hostPort().equals(key)) {
                return backend;
            }
        }

        return null;
    }

    private boolean aggregateChunksForLegacyHttp(ProxyRequest request) {
        return parent.getCurrentConfiguration().isHttp10BackwardCompatibilityEnabled()
                && request.getRequest().version() == HttpVersion.HTTP_1_0;
    }

    private Publisher<Void> serveServiceUnavailable(ProxyRequest request) {
        if (request.getResponse().hasSentHeaders()) {
            return Mono.empty();
        }

        final MapResult action = Objects.requireNonNull(request.getAction());
        SimpleHTTPResponse res = parent.getMapper().mapServiceUnavailableError(action.getRouteId());
        int code = res.errorCode();
        String resource = res.resource();
        List<CustomHeader> customHeaders = res.customHeaders();
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

    private Publisher<Void> serveFromCache(ProxyRequest request, final BackendHealthStatus healthStatus) {
        ContentsCache.ContentSender cacheSender = parent.getCache().getCacheSender(request);
        if (cacheSender == null) {
            // content non cached, forwarding and caching...
            return forward(request, true, healthStatus);
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
            applyCustomResponseHeaders(request);
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

    @VisibleForTesting
    public ConnectionsManager getConnectionsManager() {
        return connectionsManager;
    }

}
