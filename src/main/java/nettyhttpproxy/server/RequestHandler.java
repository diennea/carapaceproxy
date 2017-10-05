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
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.MapResult;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.client.EndpointNotAvailableException;
import nettyhttpproxy.client.impl.EndpointConnectionImpl;

/**
 * Keeps state for a single HttpRequest.
 */
public class RequestHandler {

    private static final Logger LOG = Logger.getLogger(RequestHandler.class.getName());
    private final long id;
    final HttpRequest request;
    private MapResult action;
    private EndpointConnection connectionToEndpoint;
    private final ClientConnectionHandler connectionToClient;
    private final ChannelHandlerContext channelToClient;

    public RequestHandler(long id, HttpRequest request, ClientConnectionHandler parent, ChannelHandlerContext channelToClient) {
        this.id = id;
        this.request = request;
        this.connectionToClient = parent;
        this.channelToClient = channelToClient;
    }

    public void start() {
        action = connectionToClient.mapper.map(request);
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
            case CACHE:
                try {
                    connectionToEndpoint = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port, false));
//                    LOG.info(this + " got connecton " + connectionToEndpoint + " handle");
                } catch (EndpointNotAvailableException err) {
                    LOG.info(this + " error on endpoint " + action + ": " + err);
                    return;
                }
//                        LOG.info("sending http request to " + action.host + ":" + action.port);
                connectionToEndpoint.sendRequest(request, this);

                return;
            case PIPE:
                try {
                    connectionToEndpoint = connectionToClient.connectionsManager.getConnection(new EndpointKey(action.host, action.port, true));
                } catch (EndpointNotAvailableException err) {
                    LOG.info(this + " error on endpoint " + action + ": " + err);
                    return;
                }
                connectionToEndpoint.sendRequest(request, this);
                return;
            default:
                throw new IllegalStateException("not yet implemented");
        }

    }

    void lastHttpContent(LastHttpContent trailer) {
//        LOG.info(this + " got LastHttpContent " + trailer + " connection: " + connectionToEndpoint + " action:" + action.action);
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

    void continueRequest(HttpContent httpContent) {
        switch (action.action) {
            case DEBUG:
                continueDebugMessage(httpContent, httpContent);
                break;
        }
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
            releaseConnectionToEndpoint(true);
            return;
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
            if (msg instanceof HttpMessage) {
                HttpMessage httpMessage = (HttpMessage) msg;
                long contentLength = HttpUtil.getContentLength(httpMessage, -1);

                if (contentLength < 0) {
                    connectionToClient.keepAlive = false;
//                    LOG.log(Level.SEVERE, "response without contentLength" + contentLength + " keepalive will be disabled");
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

}
