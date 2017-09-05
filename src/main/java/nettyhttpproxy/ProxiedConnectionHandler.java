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
package nettyhttpproxy;

import nettyhttpproxy.client.ConnectionsManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.client.EndpointNotAvailableException;
import nettyhttpproxy.client.impl.EndpointConnectionImpl;

public class ProxiedConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private final EndpointMapper mapper;
    private HttpRequest request;
    private MapResult action;
    private EndpointConnection connection;
    private final StringBuilder output = new StringBuilder();
    private final ConnectionsManager connectionsManager;
    private volatile boolean keepAlive = false;

    public ProxiedConnectionHandler(EndpointMapper mapper, ConnectionsManager connectionsManager) {
        this.mapper = mapper;
        this.connectionsManager = connectionsManager;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected synchronized void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = this.request = (HttpRequest) msg;

            action = mapper.map(request);
            LOG.log(Level.INFO, "Mapped " + request.uri() + " to " + action);
            switch (action.action) {
                case NOTFOUND:
                    if (HttpUtil.is100ContinueExpected(request)) {
                        send100Continue(ctx);
                    }
                    return;
                case DEBUG:
                    if (HttpUtil.is100ContinueExpected(request)) {
                        send100Continue(ctx);
                    }
                    startDebugMessage(request);
                    return;
                case PROXY:
                case CACHE:
                    try {
                        connection = connectionsManager.getConnection(new EndpointKey(action.host, action.port, false));
                    } catch (EndpointNotAvailableException err) {
                        sendServiceNotAvailable(ctx, err + "");
                        return;
                    }
//                        LOG.info("sending http request to " + action.host + ":" + action.port);
                    connection.sendRequest(request, this, ctx);

                    return;
                case PIPE:
                    try {
                        connection = connectionsManager.getConnection(new EndpointKey(action.host, action.port, true));
                    } catch (EndpointNotAvailableException err) {
                        sendServiceNotAvailable(ctx, err + "");
                        return;
                    }
                    connection.sendRequest(request, this, ctx);
                    return;
                default:
                    throw new IllegalStateException("not yet implemented");
            }

        } else if (msg instanceof LastHttpContent) {
            LastHttpContent trailer = (LastHttpContent) msg;
            LOG.info("got LastHttpContent " + trailer + " connection: " + connection);
            HttpContent httpContent = (HttpContent) msg;
            switch (action.action) {
                case DEBUG: {
                    serveDebugMessage(httpContent, msg, trailer, ctx);
                    break;
                }
                case NOTFOUND: {
                    serveNotFoundMessage(ctx);
                    break;
                }
                case CACHE:
                case PROXY: {
                    connection.sendLastHttpContent(trailer.copy(), this, ctx);
                    break;
                }
                default:
                    throw new IllegalStateException("not yet implemented");
            }
        } else if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;
            switch (action.action) {
                case DEBUG:
                    continueDebugMessage(httpContent, msg);
                    break;
            }
        }

    }

    private void serveNotFoundMessage(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HTTP_1_1, NOT_FOUND);
        if (!writeResponse(response, ctx)) {
            // If keep-alive is off, close the connection once the content is fully written.
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void serveDebugMessage(HttpContent httpContent, Object msg, LastHttpContent trailer, ChannelHandlerContext ctx) {
        continueDebugMessage(httpContent, msg);
        FullHttpResponse response = new DefaultFullHttpResponse(
            HTTP_1_1, trailer.decoderResult().isSuccess() ? OK : BAD_REQUEST,
            Unpooled.copiedBuffer(output.toString(), CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        if (!writeResponse(response, ctx)) {
            // If keep-alive is off, close the connection once the content is fully written.
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
    private static final Logger LOG = Logger.getLogger(ProxiedConnectionHandler.class.getName());

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
        output.append("WELCOME TO THE WILD WILD WEB SERVER\r\n");
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

    private boolean writeResponse(FullHttpResponse response, ChannelHandlerContext ctx) {
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
        ctx.writeAndFlush(response, ctx.voidPromise());

        return keepAlive;
    }

    private void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, CONTINUE);
        ctx.writeAndFlush(response, ctx.voidPromise());
    }

    private void sendServiceNotAvailable(ChannelHandlerContext ctx, String cause) {
        LOG.info("sendServiceNotAvailable due to " + cause + " to " + ctx);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        ctx.writeAndFlush(response).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                LOG.info("sendServiceNotAvailable result: " + future.isSuccess() + ", cause " + future.cause());
                if (future.isSuccess()) {
                    ctx.close();
                }
            }
        });
    }

//    LOG.info("connection is to be closed");
//            releaseConnection(true);
//            peerChannel.close();
    public void receivedFromRemote(HttpObject msg, ChannelHandlerContext channelToClient) {
        LOG.log(Level.INFO, "received from remote server:{0} keepAlive {1}", new Object[]{msg, keepAlive});
        channelToClient.writeAndFlush(msg).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                boolean returnConnection = false;
                if (future.isSuccess()) {
                    if (msg instanceof LastHttpContent) {
                        LOG.log(Level.SEVERE, "sent to client last " + msg);
                        returnConnection = true;
                    } else {
                        LOG.log(Level.SEVERE, "sent to client " + msg);
                    }
                } else {
                    boolean isOpen = channelToClient.channel().isOpen();
                    LOG.log(Level.SEVERE, "bad error writing to client, isOpen " + isOpen, future.cause());
                    returnConnection = true;
                }
                if (!keepAlive && msg instanceof LastHttpContent) {
                    channelToClient.close();
                }
                if (returnConnection) {
                    releaseConnectionToEndpoint(!keepAlive);
                }
            }
        });
    }

    private synchronized void releaseConnectionToEndpoint(boolean forceClose) {
        if (connection != null) {
            LOG.log(Level.INFO, "release connection {0}", connection);
            connection.release(forceClose);
            connection = null;
        }
    }

    public void readCompletedFromRemote(ChannelHandlerContext channel) {
        channel.flush();
    }

    public void errorSendingRequest(EndpointConnectionImpl aThis, ChannelHandlerContext peerChannel, Throwable error) {
        mapper.endpointFailed(aThis.getKey(), error);
        LOG.info("errorSendingRequest " + aThis);
        sendServiceNotAvailable(peerChannel, "errorSendingRequest");
    }

    public void lastHttpContentSent(ChannelHandlerContext peerChannel) {
        if (!HttpUtil.isKeepAlive(request)) {
            keepAlive = false;
        }
        LOG.info("lastHttpContentSent, keepAlive:"+keepAlive);
    }

}
