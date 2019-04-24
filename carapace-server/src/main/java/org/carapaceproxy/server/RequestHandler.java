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
package org.carapaceproxy.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpStatusClass.SUCCESS;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.MapResult;
import org.carapaceproxy.client.EndpointConnection;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.client.EndpointNotAvailableException;
import org.carapaceproxy.client.impl.EndpointConnectionImpl;
import static org.carapaceproxy.server.StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.filters.UrlEncodedQueryString;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.StatsLogger;
import static org.carapaceproxy.server.mapper.StandardEndpointMapper.HeaderMode.HEADER_MODE_ADD;
import static org.carapaceproxy.server.mapper.StandardEndpointMapper.HeaderMode.HEADER_MODE_REMOVE;
import static org.carapaceproxy.server.mapper.StandardEndpointMapper.HeaderMode.HEADER_MODE_SET;

/**
 * Keeps state for a single HttpRequest.
 */
public class RequestHandler {

    private static final Logger LOG = Logger.getLogger(RequestHandler.class.getName());
    private static final AtomicLong TIME_TRACKER = new AtomicLong();
    private final long id;
    private final HttpRequest request;
    private final List<RequestFilter> filters;
    private MapResult action;
    private ContentsCache.ContentReceiver cacheReceiver;
    private ContentsCache.ContentSender cacheSender;
    private AtomicReference<EndpointConnection> connectionToEndpoint = new AtomicReference<>();
    private final ClientConnectionHandler connectionToClient;
    private final ChannelHandlerContext channelToClient;
    private final AtomicReference<Runnable> onRequestFinished;
    private final StatsLogger logger;
    private final BackendHealthManager backendHealthManager;
    private final RequestsLogger requestsLogger;
    private String userId;
    private String sessionId;
    private final String uri;
    private long startTs;
    private long backendStartTs = 0;
    private volatile long lastActivity;
    private volatile boolean headerSent = false;
    private UrlEncodedQueryString queryString;

    public RequestHandler(long id, HttpRequest request, List<RequestFilter> filters, StatsLogger logger,
            ClientConnectionHandler parent, ChannelHandlerContext channelToClient, Runnable onRequestFinished,
            BackendHealthManager backendHealthManager, RequestsLogger requestsLogger) {
        this.id = id;
        this.uri = request.uri();
        this.request = request;
        this.connectionToClient = parent;
        this.channelToClient = channelToClient;
        this.filters = filters;
        this.logger = logger;
        this.backendHealthManager = backendHealthManager;
        this.onRequestFinished = new AtomicReference<>(onRequestFinished);
        this.requestsLogger = requestsLogger;
    }

    public long getId() {
        return id;
    }

    public String getUri() {
        return uri;
    }

    MapResult getAction() {
        return action;
    }

    long getStartTs() {
        return startTs;
    }

    long getBackendStartTs() {
        return backendStartTs;
    }

    long getLastActivity() {
        return lastActivity;
    }

    boolean isServedFromCache() {
        return cacheSender != null;
    }

    InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channelToClient.channel().remoteAddress();
    }

    InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channelToClient.channel().localAddress();
    }

    private void fireRequestFinished() {
        Runnable handler = onRequestFinished.getAndSet(null);
        if (handler != null) {
            handler.run();
        }
    }

    private static final UrlEncodedQueryString EMPTY = UrlEncodedQueryString.create();

    public UrlEncodedQueryString getQueryString() {
        if (queryString != null) {
            return queryString;
        }
        queryString = parseQueryString(uri);
        return queryString;
    }

    public static UrlEncodedQueryString parseQueryString(String uri) {
        int pos = uri.indexOf('?');
        if (pos < 0 || pos == uri.length() - 1) {
            return EMPTY;
        } else {
            return UrlEncodedQueryString.parse(uri.substring(pos + 1));
        }

    }

    public void start() {
        startTs = System.currentTimeMillis();
        lastActivity = startTs;
        for (RequestFilter filter : filters) {
            filter.apply(request, connectionToClient, this);
        }
        action = connectionToClient.mapper.map(request, userId, sessionId, backendHealthManager, this);
        if (action == null) {
            LOG.severe("Mapper returned NULL action !");
            action = MapResult.INTERNAL_ERROR(MapResult.NO_ROUTE);
        }
        //LOG.info("map " + request.uri() + " to " + action.action);
        Counter requestsPerUser;
        if (userId != null) {
            requestsPerUser = logger.getCounter("requests_" + userId + "_count");
        } else {
            requestsPerUser = logger.getCounter("requests_nobody_count");
        }
        requestsPerUser.inc();

        if (LOG.isLoggable(Level.FINER)) {
            LOG.log(Level.FINER, "{0} Mapped {1} to {2}, userid {3}", new Object[]{this, uri, action, userId});
        }
        switch (action.action) {
            case NOTFOUND:
            case INTERNAL_ERROR:
            case SYSTEM:
            case STATIC:
            case ACME_CHALLENGE:
                return;
            case PROXY:
                try {
//                    LOG.log(Level.SEVERE, "TIME"+TIME_TRACKER.incrementAndGet()+" start " + this + " thread " + Thread.currentThread().getName());
                    EndpointConnection connection = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port));
                    connectionToEndpoint.set(connection);
                    connection.sendRequest(request, this);
                } catch (EndpointNotAvailableException err) {
                    fireRequestFinished();
                    LOG.log(Level.INFO, "{0} error on endpoint {1}: {2}", new Object[]{this, action, err});
                    return;
                }
                return;
            case CACHE:
                try {
//                    LOG.log(Level.SEVERE, "TIME"+TIME_TRACKER.incrementAndGet()+" startc " + this + " thread " + Thread.currentThread().getName());
                    cacheSender = connectionToClient.cache.serveFromCache(this);
                    if (cacheSender != null) {
                        return;
                    }
                    EndpointConnection connection = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port));
                    connectionToEndpoint.set(connection);
                    cacheReceiver = connectionToClient.cache.startCachingResponse(request);
                    if (cacheReceiver != null) {
                        // https://tools.ietf.org/html/rfc7234#section-4.3.4
                        cleanRequestFromCacheValidators(request);
                    }
                    connection.sendRequest(request, this);
                } catch (EndpointNotAvailableException err) {
                    fireRequestFinished();
                    LOG.log(Level.INFO, "{0} error on endpoint {1}: {2}", new Object[]{this, action, err});
                    return;
                }
                return;

            default:
                throw new IllegalStateException("not yet implemented");
        }

    }

    void continueClientRequest(HttpContent httpContent) {
        if (cacheSender != null) {
            LOG.log(Level.SEVERE, "{0} swallow chunk {1}, I am serving a cache content {2}", new Object[]{this, httpContent, cacheReceiver});
            return;
        }
        switch (action.action) {
            case STATIC:
            case ACME_CHALLENGE:
            case INTERNAL_ERROR:
            case NOTFOUND:
                break;
            case SYSTEM:
                continueDebugMessage(httpContent, httpContent);
                break;
            case PROXY:
            case CACHE:
                EndpointConnection connection = connectionToEndpoint.get();
                if (connection == null) {
                    LOG.log(Level.INFO, "{0} swallow continued content {1}. Not connected", new Object[]{this, httpContent});
                    return;
                }
                connection.sendChunk(httpContent.retain(), this);
                break;
            default:
                throw new IllegalStateException("not yet implemented action: " + action.action);
        }
    }

    public HttpRequest getRequest() {
        return request;
    }

    void clientRequestFinished(LastHttpContent trailer) {

        if (cacheSender != null) {
            serveFromCache();
            return;
        }

        if (action == null) {
            LOG.log(Level.SEVERE, "Impossible action NULL on request {0}", this.uri);
            serveInternalErrorMessage(true);
            return;
        }

        HttpContent httpContent = (HttpContent) trailer;
        switch (action.action) {
            case SYSTEM: {
                startDebugMessage(request);
                serveDebugMessage(httpContent, trailer, trailer);
                break;
            }
            case NOTFOUND: {
                serveNotFoundMessage();
                break;
            }
            case INTERNAL_ERROR: {
                serveInternalErrorMessage(false);
                break;
            }
            case STATIC:
            case ACME_CHALLENGE: {
                serveStaticMessage();
                break;
            }
            case CACHE:
            case PROXY: {
                EndpointConnection connection = connectionToEndpoint.get();
                if (connection == null) {
                    sendServiceNotAvailable();
                } else {
                    connection.sendLastHttpContent(trailer.copy(), this);
                }
                break;
            }
            default:
                throw new IllegalStateException("not yet implemented");
        }
    }

    private final StringBuilder output = new StringBuilder();

    private void serveNotFoundMessage() {
        MapResult fromDefault = connectionToClient.mapper.mapDefaultPageNotFound(request, action.routeid);
        int code = 0;
        String resource = null;
        if (fromDefault != null) {
            code = fromDefault.getErrorcode();
            resource = fromDefault.getResource();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_NOT_FOUND;
        }
        if (code <= 0) {
            code = 404;
        }

        FullHttpResponse response = connectionToClient.staticContentsManager.buildResponse(code, resource);        
        if (!writeSimpleResponse(response)) {
            forceCloseChannelToClient();
        }
    }

    private void forceCloseChannelToClient() {
        // If keep-alive is off, close the connection once the content is fully written.
        channelToClient.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    private void serveInternalErrorMessage(boolean forceClose) {
        MapResult fromDefault = connectionToClient.mapper.mapDefaultInternalError(request, action.routeid);
        int code = 0;
        String resource = null;
        if (fromDefault != null) {
            code = fromDefault.getErrorcode();
            resource = fromDefault.getResource();
        }
        if (resource == null) {
            resource = StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR;
        }
        if (code <= 0) {
            code = 500;
        }

        FullHttpResponse response = connectionToClient.staticContentsManager.buildResponse(code, resource);
        addCustomResponseHeaders(response);

        if (!writeSimpleResponse(response) || forceClose) {
            // If keep-alive is off, close the connection once the content is fully written.
            forceCloseChannelToClient();
        }
    }

    private void serveStaticMessage() {
        FullHttpResponse response
                = connectionToClient.staticContentsManager.buildResponse(action.errorcode, action.resource);
        addCustomResponseHeaders(response);
        if (!writeSimpleResponse(response)) {
            // If keep-alive is off, close the connection once the content is fully written.
            forceCloseChannelToClient();
        }
    }

    private void serveDebugMessage(HttpContent httpContent, Object msg, LastHttpContent trailer) {
        continueDebugMessage(httpContent, msg);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, trailer.decoderResult().isSuccess() ? OK : BAD_REQUEST,
                Unpooled.copiedBuffer(output.toString(), CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        if (!writeSimpleResponse(response)) {
            // If keep-alive is off, close the connection once the content is fully written.
            forceCloseChannelToClient();
        }
    }

    private void continueDebugMessage(HttpContent httpContent, Object msg) {
        ByteBuf content = httpContent.content();
        if (content.isReadable()) {
            output.append("CONTENT: ");
            output.append(content.toString(CharsetUtil.UTF_8));
            output.append("\r\n");
            appendDecoderResult(output, request);
        }
        if (msg instanceof LastHttpContent) {
            output.append("END OF CONTENT\r\n");
            LastHttpContent trailer = (LastHttpContent) msg;
            if (!trailer.trailingHeaders().isEmpty()) {
                output.append("\r\n");
                for (CharSequence name : trailer.trailingHeaders().names()) {
                    for (CharSequence value : trailer.trailingHeaders().getAll(name)) {
                        output.append("TRAILING HEADER: ");
                        output.append(name).append(" = ").append(value).append("\r\n");
                    }
                }
                output.append("\r\n");
            }
        }
    }

    private void startDebugMessage(HttpRequest request1) {
        output.setLength(0);
        output.append("WELCOME TO THIS PROXY SERVER\r\n");
        output.append("===================================\r\n");
        output.append("VERSION: ").append(request1.protocolVersion()).append("\r\n");
        output.append("HOSTNAME: ").append(request1.headers().get(HttpHeaderNames.HOST, "unknown")).append("\r\n");
        output.append("REQUEST_URI: ").append(request1.uri()).append("\r\n\r\n");
        HttpHeaders headers = request1.headers();
        if (!headers.isEmpty()) {
            for (Map.Entry<String, String> h : headers) {
                CharSequence key = h.getKey();
                CharSequence value = h.getValue();
                output.append("HEADER: ").append(key).append(" = ").append(value).append("\r\n");
            }
            output.append("\r\n");
        }
        appendDecoderResult(output, request1);
    }

    private static void appendDecoderResult(StringBuilder buf, HttpObject o) {
        DecoderResult result = o.decoderResult();
        if (result.isSuccess()) {
            return;
        }

        buf.append(".. WITH DECODER FAILURE: ");
        buf.append(result.cause());
        buf.append("\r\n");
    }

    private boolean writeSimpleResponse(FullHttpResponse response) {
        fireRequestFinished();

        if (headerSent) {
            LOG.log(Level.INFO, "{0}: headers for already sent to client, cannot send static response", this);
            return true;
        }

        // Decide whether to close the connection or not.
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        // Build the response object.
        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            if (response.content() != null) {
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            }
            // Add keep alive header as per:
            // - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // Write the response.
        channelToClient.writeAndFlush(response).addListener(future -> {
            lastHttpContentSent();
        });

        return keepAlive;
    }

    private void sendServiceNotAvailable() {
//        LOG.info(this + " sendServiceNotAvailable due to " + cause + " to " + ctx);
        FullHttpResponse response
                = connectionToClient.staticContentsManager.buildResponse(500, DEFAULT_INTERNAL_SERVER_ERROR);
        channelToClient.writeAndFlush(response).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                LOG.log(Level.INFO, "{0} sendServiceNotAvailable result: {1}, cause {2}",
                        new Object[]{this, future.isSuccess(), future.cause()});
                channelToClient.close();
                lastHttpContentSent();
            }
        });

    }

    public void lastHttpContentSent() {
        lastActivity = System.currentTimeMillis();
        connectionToClient.lastHttpContentSent(this);
        requestsLogger.logRequest(this);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 67 * hash + (int) (this.id ^ (this.id >>> 32));
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final RequestHandler other = (RequestHandler) obj;
        if (this.id != other.id) {
            return false;
        }
        return true;
    }

    public EndpointConnection getConnectionToEndpoint() {
        return connectionToEndpoint.get();
    }

    public boolean errorSendingRequest(EndpointConnectionImpl connection, Throwable cause) {
        boolean ok = releaseConnectionToEndpoint(true, connection);
        if (ok) {
            connectionToClient.errorSendingRequest(this, connection, channelToClient, cause);
            sendServiceNotAvailable();
        }
        return ok;

    }

    public void receivedFromRemote(HttpObject msg, EndpointConnection connection) {
        if (backendStartTs == 0) {
            backendStartTs = System.currentTimeMillis();
        }
        if (connectionToClient == null) {
            // client no more connected
            if (cacheReceiver != null) {
                cacheReceiver.abort();
            }
            releaseConnectionToEndpoint(true, connection);
            return;
        }
        if (cacheReceiver != null) {
            // msg object won't be cached as-is but the cache will retain a clone of it
            cacheReceiver.receivedFromRemote(msg);
            if (msg instanceof HttpResponse) {
                HttpResponse httpMessage = (HttpResponse) msg;
                cleanResponseForCachedData(httpMessage);
            }
        }

        addCustomResponseHeaders(msg);

        channelToClient.writeAndFlush(msg).addListener((Future<? super Void> future) -> {
            boolean returnConnection = false;
            if (future.isSuccess()) {
                if (msg instanceof LastHttpContent) {
//                    LOG.log(Level.INFO, this + " sent back to client last " + msg);
                    returnConnection = true;
                } else {
//                    LOG.log(Level.INFO, this + " sent back to client " + msg);
                }
            } else {
                boolean isOpen = channelToClient.channel().isOpen();
                LOG.log(Level.FINE, this + " bad error writing to client, isOpen " + isOpen, future.cause());
                returnConnection = true;
            }
            if (msg instanceof HttpResponse) {
                headerSent = true;
                HttpResponse httpMessage = (HttpResponse) msg;
                if (SUCCESS == httpMessage.status().codeClass()) {
                    long contentLength = HttpUtil.getContentLength(httpMessage, -1);
                    String transferEncoding = httpMessage.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
                    if (contentLength < 0 && !"chunked".equals(transferEncoding)) {
                        connectionToClient.keepAlive = false;
                        LOG.log(Level.SEVERE, uri + " response without contentLength{0} and with Transfer-Encoding {1}. keepalive will be disabled " + msg, new Object[]{contentLength, transferEncoding});
                    }
                }
            }
            boolean keepAlive1 = connectionToClient.isKeepAlive() && future.isSuccess();
            // LOG.log(Level.INFO, this + " returnConnection:" + returnConnection + ", keepAlive1:" + keepAlive1 + " connecton " + connectionToEndpoint);
            if (!keepAlive1 && msg instanceof LastHttpContent) {
                connectionToClient.refuseOtherRequests = true;
                channelToClient.close();
            }
            if (returnConnection) {
                releaseConnectionToEndpoint(!keepAlive1, connection);
            }
        });
    }

    private void addCustomResponseHeaders(HttpObject msg) {
        // Custom response Headers
        if (msg instanceof HttpResponse && action != null && action.customHeaders != null) {
            HttpHeaders headers = ((HttpResponse) msg).headers();
            action.customHeaders.forEach(customHeader -> {
                if (HEADER_MODE_SET.equals(customHeader.getMode()) ||
                        HEADER_MODE_REMOVE.equals(customHeader.getMode())) {
                    headers.remove(customHeader.getName());
                }
                if (HEADER_MODE_SET.equals(customHeader.getMode()) ||
                        HEADER_MODE_ADD.equals(customHeader.getMode())) {
                    headers.add(customHeader.getName(), customHeader.getValue());
                }
            });
        }
    }

    private boolean releaseConnectionToEndpoint(boolean forceClose, EndpointConnection current) {
        if (connectionToEndpoint.compareAndSet(current, null)) {
            fireRequestFinished();
            if (current != null) {
                // return the connection the pool
//            LOG.log(Level.INFO, this + " release connection {0}, forceClose {1}", new Object[]{connectionToEndpoint, forceClose});
                current.release(forceClose, this);
            }
            return true;
        } else {
            return false;
        }
    }

    public void readCompletedFromRemote() {
        channelToClient.flush();
    }

    @Override
    public String toString() {
        return "RequestHandler{" + "id=" + id + ", connectionToEndpoint=" + connectionToEndpoint + ", connectionToClient=" + connectionToClient + ", last " + lastActivity + '}';
    }

    public boolean isKeepAlive() {
        return HttpUtil.isKeepAlive(request);
    }

    public void serveFromCache() {
        ContentsCache.ContentPayload payload = cacheSender.getCached();
        sendCachedChunk(payload, 0);
    }

    private void sendCachedChunk(ContentsCache.ContentPayload payload, int i) {
        int size = payload.getChunks().size();
        HttpObject object = payload.getChunks().get(i);
        boolean notModified;
        boolean isLastHttpContent = object instanceof LastHttpContent;
        if (object instanceof HttpResponse) {

            HttpHeaders requestHeaders = request.headers();
            long ifModifiedSince = requestHeaders.getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, -1);
            if (ifModifiedSince != -1 && payload.getLastModified() > 0 && ifModifiedSince >= payload.getLastModified()) {
                HttpHeaders newHeaders = new DefaultHttpHeaders();
                newHeaders.set("Last-Modified", new java.util.Date(payload.getLastModified()));
                newHeaders.set("Expires", new java.util.Date(payload.getExpiresTs()));
                newHeaders.add("X-Cached", "yes; ts=" + payload.getCreationTs());
                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED,
                        Unpooled.EMPTY_BUFFER, newHeaders, new DefaultHttpHeaders());
                object = resp;
                notModified = true;
            } else {
                HttpResponse resp = (HttpResponse) object;
                HttpHeaders headers = new DefaultHttpHeaders();
                headers.add(resp.headers());
                headers.remove(HttpHeaderNames.EXPIRES);
                headers.remove(HttpHeaderNames.ACCEPT_RANGES);
                headers.remove(HttpHeaderNames.ETAG);
                headers.add("X-Cached", "yes; ts=" + payload.getCreationTs());
                headers.add("Expires", new java.util.Date(payload.getExpiresTs()));

                object = new DefaultHttpResponse(resp.protocolVersion(), resp.status(), headers);
                long contentLength = HttpUtil.getContentLength(resp, -1);
                String transferEncoding = resp.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
                if (contentLength < 0 && !"chunked".equals(transferEncoding)) {
                    connectionToClient.keepAlive = false;
                    LOG.log(Level.SEVERE, uri + " response without contentLength{0} and with Transfer-Encoding {1}. keepalive will be disabled " + resp, new Object[]{contentLength, transferEncoding});
                }
                notModified = false;
            }
        } else {
            object = ContentsCache.cloneHttpObject(object);
            notModified = false;
        }
        HttpObject _object = object;

        channelToClient.writeAndFlush(_object)
                .addListener((g) -> {
                    if (isLastHttpContent || notModified) {
                        lastHttpContentSent();
                        boolean keepAlive1 = connectionToClient.isKeepAlive();
                        if (!keepAlive1) {
                            connectionToClient.refuseOtherRequests = true;
                            channelToClient.close();
                        }
                    }
                    if (i + 1 < size && g.isSuccess() && !notModified) {
                        sendCachedChunk(payload, i + 1);
                    } else {
                        fireRequestFinished();
                    }
                });
    }

    private void cleanRequestFromCacheValidators(HttpRequest request) {
        HttpHeaders headers = request.headers();
        headers.remove(HttpHeaderNames.IF_MATCH);
        headers.remove(HttpHeaderNames.IF_MODIFIED_SINCE);
        headers.remove(HttpHeaderNames.IF_NONE_MATCH);
        headers.remove(HttpHeaderNames.IF_RANGE);
        headers.remove(HttpHeaderNames.IF_UNMODIFIED_SINCE);
        headers.remove(HttpHeaderNames.ETAG);
        headers.remove(HttpHeaderNames.CONNECTION);
    }

    private void cleanResponseForCachedData(HttpResponse httpMessage) {
        HttpHeaders headers = httpMessage.headers();
        if (!headers.contains(HttpHeaderNames.EXPIRES)) {
            headers.add("Expires", new java.util.Date(connectionToClient.cache.computeDefaultExpireDate()));
        }
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void failIfStuck(long now, int stuckRequestTimeout, Runnable onStuck) {
        long delta = now - lastActivity;
        if (delta >= stuckRequestTimeout) {
            LOG.log(Level.INFO, this + " connection appears stuck " + connectionToEndpoint + ", on request " + uri + " for userId: " + userId);
            onStuck.run();
            releaseConnectionToEndpoint(true, connectionToEndpoint.get());
            serveInternalErrorMessage(true);
        }
    }

    public void messageSentToBackend(EndpointConnectionImpl aThis) {
        lastActivity = System.currentTimeMillis();
    }

    public void badErrorOnRemote(Throwable cause) {
        LOG.log(Level.INFO, this + " badErrorOnRemote " + cause);
        releaseConnectionToEndpoint(true, connectionToEndpoint.get());
        serveInternalErrorMessage(true);
    }

}
