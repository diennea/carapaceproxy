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
package nettyhttpproxy.server;

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
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpStatusClass.SUCCESS;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.MapResult;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.client.EndpointNotAvailableException;
import nettyhttpproxy.client.impl.EndpointConnectionImpl;
import nettyhttpproxy.server.cache.ContentsCache;

/**
 * Keeps state for a single HttpRequest.
 */
public class RequestHandler {

    private static final Logger LOG = Logger.getLogger(RequestHandler.class.getName());
    private final long id;
    private final HttpRequest request;
    private final List<RequestFilter> filters;
    private MapResult action;
    private ContentsCache.ContentReceiver cacheReceiver;
    private ContentsCache.ContentSender cacheSender;
    private EndpointConnection connectionToEndpoint;
    private final ClientConnectionHandler connectionToClient;
    private final ChannelHandlerContext channelToClient;

    public RequestHandler(long id, HttpRequest request, List<RequestFilter> filters,
        ClientConnectionHandler parent, ChannelHandlerContext channelToClient) {
        this.id = id;
        this.request = request;
        this.connectionToClient = parent;
        this.channelToClient = channelToClient;
        this.filters = filters;
    }

    public void start() {
        action = connectionToClient.mapper.map(request);
        for (RequestFilter filter : filters) {
            filter.apply(request, connectionToClient);
        }
//        LOG.log(Level.INFO, this + " Mapped " + request.uri() + " to " + action);
        switch (action.action) {
            case NOTFOUND:
                if (HttpUtil.is100ContinueExpected(request)) {
                    send100Continue();
                }
                return;
            case DEBUG:
                if (HttpUtil.is100ContinueExpected(request)) {
                    send100Continue();
                }
                return;
            case PROXY:
                try {
                    connectionToEndpoint = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port));
                    connectionToEndpoint.sendRequest(request, this);
                } catch (EndpointNotAvailableException err) {
                    LOG.info(this + " error on endpoint " + action + ": " + err);
                    return;
                }
                return;
            case CACHE:
                try {
                    cacheSender = connectionToClient.cache.serveFromCache(this);
                    if (cacheSender != null) {
                        return;
                    }
                    connectionToEndpoint = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port));
                    cacheReceiver = connectionToClient.cache.startCachingResponse(request);
                    if (cacheReceiver != null) {
                        // https://tools.ietf.org/html/rfc7234#section-4.3.4
                        cleanRequestFromCacheValidators(request);
                    }
                    connectionToEndpoint.sendRequest(request, this);
                } catch (EndpointNotAvailableException err) {
                    LOG.info(this + " error on endpoint " + action + ": " + err);
                    return;
                }
                return;

            default:
                throw new IllegalStateException("not yet implemented");
        }

    }

    void continueClientRequest(HttpContent httpContent) {
        if (cacheSender != null) {
            LOG.severe(this + " swallow chunk " + httpContent + ", I am serving a cache content " + cacheReceiver);
            return;
        }
        switch (action.action) {
            case DEBUG:
                continueDebugMessage(httpContent, httpContent);
                break;
            case PROXY:
            case CACHE:
                if (connectionToEndpoint == null) {
                    LOG.info(this + " swallow continued content " + httpContent + ". Not connected");
                    return;
                }
                connectionToEndpoint.continueRequest(httpContent.retain());
                break;
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

        HttpContent httpContent = (HttpContent) trailer;
        switch (action.action) {
            case DEBUG: {
                startDebugMessage(request);
                serveDebugMessage(httpContent, trailer, trailer);
                break;
            }
            case NOTFOUND: {
                serveNotFoundMessage();
                break;
            }
            case CACHE:
            case PROXY: {
                if (connectionToEndpoint == null) {
                    sendServiceNotAvailable();
                } else {
                    connectionToEndpoint.sendLastHttpContent(trailer.copy(), this);
                }
                break;
            }
            default:
                throw new IllegalStateException("not yet implemented");
        }
    }

    private final StringBuilder output = new StringBuilder();

    private void serveNotFoundMessage() {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HTTP_1_1, NOT_FOUND);
        if (!writeResponse(response)) {
            // If keep-alive is off, close the connection once the content is fully written.
            channelToClient.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void serveDebugMessage(HttpContent httpContent, Object msg, LastHttpContent trailer) {
        continueDebugMessage(httpContent, msg);
        FullHttpResponse response = new DefaultFullHttpResponse(
            HTTP_1_1, trailer.decoderResult().isSuccess() ? OK : BAD_REQUEST,
            Unpooled.copiedBuffer(output.toString(), CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        if (!writeResponse(response)) {
            // If keep-alive is off, close the connection once the content is fully written.
            channelToClient.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
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

    private boolean writeResponse(FullHttpResponse response) {
        // Decide whether to close the connection or not.
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        // Build the response object.
        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
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

    private void send100Continue() {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, CONTINUE);
        channelToClient.writeAndFlush(response, channelToClient.voidPromise());
    }

    private void sendServiceNotAvailable() {
//        LOG.info(this + " sendServiceNotAvailable due to " + cause + " to " + ctx);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        channelToClient.writeAndFlush(response).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                LOG.info(this + " sendServiceNotAvailable result: " + future.isSuccess() + ", cause " + future.cause());
                channelToClient.close();
                releaseConnectionToEndpoint(false);
                lastHttpContentSent();
            }
        });
    }

    public void lastHttpContentSent() {
        connectionToClient.lastHttpContentSent(this);
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

    public void errorSendingRequest(EndpointConnectionImpl connectionToEndpoint, Throwable cause) {
        connectionToClient.errorSendingRequest(this, connectionToEndpoint, channelToClient, cause);
        sendServiceNotAvailable();
    }

    public void receivedFromRemote(HttpObject msg) {
        if (connectionToClient == null) {
//            LOG.log(Level.SEVERE, this + " received from remote server:{0} connection {1} server {2}", new Object[]{msg, connectionToClient, connectionToEndpoint});
            if (cacheReceiver != null) {
                cacheReceiver.abort();
            }
            releaseConnectionToEndpoint(true);
            return;
        }
        if (cacheReceiver != null) {
            cacheReceiver.receivedFromRemote(msg);
            if (msg instanceof HttpResponse) {
                HttpResponse httpMessage = (HttpResponse) msg;
                cleanResponseForCachedData(httpMessage);
            }
        }
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
//                LOG.log(Level.INFO, this + " bad error writing to client, isOpen " + isOpen, future.cause());
                returnConnection = true;
            }
            if (msg instanceof HttpResponse) {
                HttpResponse httpMessage = (HttpResponse) msg;
                if (SUCCESS == httpMessage.status().codeClass()) {
                    long contentLength = HttpUtil.getContentLength(httpMessage, -1);
                    String transferEncoding = httpMessage.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
                    if (contentLength < 0 && !"chunked".equals(transferEncoding)) {
                        connectionToClient.keepAlive = false;
                        LOG.log(Level.SEVERE, request.uri() + " response without contentLength{0} and with Transfer-Encoding {1}. keepalive will be disabled " + msg, new Object[]{contentLength, transferEncoding});
                    }
                }
            }
            boolean keepAlive1 = connectionToClient.isKeepAlive();
//            LOG.log(Level.INFO, this + " returnConnection:" + returnConnection + ", keepAlive1:" + keepAlive1 + " connecton " + connectionToEndpoint);
            if (!keepAlive1 && msg instanceof LastHttpContent) {
                connectionToClient.refuseOtherRequests = true;
                channelToClient.close();
            }
            if (returnConnection) {
                releaseConnectionToEndpoint(!keepAlive1);
            }
        });
    }

    private void releaseConnectionToEndpoint(boolean forceClose) {
        if (connectionToEndpoint != null) {
//            LOG.log(Level.INFO, this + " release connection {0}, forceClose {1}", new Object[]{connectionToEndpoint, forceClose});
            connectionToEndpoint.release(forceClose);
            connectionToEndpoint = null;
        }
    }

    public void readCompletedFromRemote() {
        channelToClient.flush();
    }

    @Override
    public String toString() {
        return "RequestHandler{" + "id=" + id + ", connectionToEndpoint=" + connectionToEndpoint + ", connectionToClient=" + connectionToClient + '}';
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

        boolean isLastHttpContent = object instanceof LastHttpContent;
        if (object instanceof HttpResponse) {
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
                LOG.log(Level.SEVERE, request.uri() + " response without contentLength{0} and with Transfer-Encoding {1}. keepalive will be disabled " + resp, new Object[]{contentLength, transferEncoding});
            }
        } else {
            object = ContentsCache.cloneHttpObject(object);
        }
        HttpObject _object = object;

        channelToClient.writeAndFlush(_object)
            .addListener((g) -> {
                if (isLastHttpContent) {
                    lastHttpContentSent();
                    boolean keepAlive1 = connectionToClient.isKeepAlive();
                    if (!keepAlive1) {
                        connectionToClient.refuseOtherRequests = true;
                        channelToClient.close();
                    }
                }
                if (i + 1 < size && g.isSuccess()) {
                    sendCachedChunk(payload, i + 1);
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

}
