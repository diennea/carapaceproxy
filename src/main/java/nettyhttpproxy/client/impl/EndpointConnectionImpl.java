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
package nettyhttpproxy.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.RequestHandler;

public class EndpointConnectionImpl implements EndpointConnection {

    private static final AtomicLong IDGENERATOR = new AtomicLong();
    private final static int CONNECT_TIMEOUT = 60000;
    private final long id = IDGENERATOR.incrementAndGet();
    private final ConnectionsManagerImpl parent;
    private final EndpointKey key;
    private final EndpointStats endpointstats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private int idleTimeout = 500_000;

    private volatile Channel channelToEndpoint;
    private volatile ChannelHandlerContext contextToEndpoint;
    private volatile boolean valid;
    private volatile RequestHandler clientSidePeerHandler;

    public EndpointConnectionImpl(EndpointKey key, ConnectionsManagerImpl parent, EndpointStats endpointstats) {
        this.key = key;
        this.parent = parent;
        this.valid = true;
        this.endpointstats = endpointstats;
        activityDone();
    }

    @Override
    public void setIdleTimeout(int timeout) {
        this.idleTimeout = timeout;
    }

    private ChannelFuture connect() {
        activityDone();
        if (channelToEndpoint != null) {
//            LOG.log(Level.INFO, "Connection {3} Already connected to {0}, channel {1}, pipeline {2}", new Object[]{key, channelToEndpoint, channelToEndpoint.pipeline(), id});
            return channelToEndpoint.newSucceededFuture();
        }
        Bootstrap b = new Bootstrap();
        b.group(parent.getGroup())
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast("client-codec", new HttpClientCodec());
                    ch.pipeline().addLast(new ReadEndpointResponseHandler());
                }
            });

        final ChannelFuture connectFuture = b.connect(key.getHost(), key.getPort());

        connectFuture.addListener((Future<Void> future) -> {
            if (future.isSuccess()) {
                endpointstats.getTotalConnections().incrementAndGet();
                endpointstats.getOpenConnections().incrementAndGet();
            }
        });
        return connectFuture;

    }

    private void activityDone() {
        endpointstats.getLastActivity().set(System.currentTimeMillis());
    }

    @Override
    public void sendRequest(HttpRequest request, RequestHandler clientSidePeerHandler) {
        ChannelFuture afterConnect = connect();
        afterConnect.addListener((Future<Void> future) -> {
            if (!future.isSuccess()) {
                valid = false;
                LOG.log(Level.INFO, "connect failed to " + key, future.cause());
                clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                return;
            }
            Channel _channelToEndpoint = afterConnect.channel();
            channelToEndpoint = _channelToEndpoint;
            _channelToEndpoint.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    valid = false;
                }
            });
            activateConnection(clientSidePeerHandler);
            endpointstats.getTotalRequests().incrementAndGet();

            channelToEndpoint.writeAndFlush(request).addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    activityDone();
                    if (!future.isSuccess()) {
                        LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed", future.cause());
                        clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                    }
//                    LOG.log(Level.SEVERE, "sendRequest finished, now " + _channelToEndpoint + " is open ? " + _channelToEndpoint.isOpen());
                    if (!_channelToEndpoint.isOpen()) {
                        valid = false;
                    }
                }
            });
        });
        try {
            afterConnect.await(CONNECT_TIMEOUT);
        } catch (InterruptedException err) {
            LOG.log(Level.SEVERE, "sendRequest interrupted during connection", err);
            clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, err);
        }
    }

    @Override
    public void release(boolean close) {
        connectionDeactivated();

        if (close) {
            destroy();
        }
        parent.returnConnection(this);

    }

    public EndpointKey getKey() {
        return key;
    }

    private static final Logger LOG = Logger.getLogger(EndpointConnectionImpl.class.getName());

    @Override
    public void sendLastHttpContent(LastHttpContent msg, RequestHandler clientSidePeerHandler) {

        if (!valid) {
            LOG.severe("sendLastHttpContent " + msg + " to " + contextToEndpoint + " . skip to invalid connection");
            return;
        }
        activityDone();
        ChannelHandlerContext _contextToEndpoint = contextToEndpoint;
        _contextToEndpoint.writeAndFlush(msg).addListener((Future<? super Void> future) -> {
            if (!future.isSuccess()) {
                LOG.log(Level.INFO, "sendLastHttpContent failed " + msg, future.cause());
            } else {
                clientSidePeerHandler.lastHttpContentSent();
            }
//            LOG.log(Level.SEVERE, "sendLastHttpContent finished, now " + _contextToEndpoint + " is open ? " + _contextToEndpoint.channel().isOpen());
            if (!_contextToEndpoint.channel().isOpen()) {
                valid = false;
            }
        });
    }

    private void detachFromClient() {
//        LOG.info("detachFromClient");
        clientSidePeerHandler = null;
    }

    void destroy() {

        if (!closed.compareAndSet(false, true)) {
            return;
        }

//        LOG.log(Level.INFO, "destroy {0}", this);
        valid = false;
        if (channelToEndpoint != null) {
            channelToEndpoint.close().addListener((future) -> {
                //                    LOG.log(Level.INFO, "connection id " + id + " to " + key + " closed now");
                endpointstats.getOpenConnections().decrementAndGet();
            });
            channelToEndpoint = null;
        }
    }

    boolean isValid() {
        return valid && (System.currentTimeMillis() - endpointstats.getLastActivity().longValue() <= idleTimeout);
    }

    private void activateConnection(RequestHandler handler) {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("this connection is already active!");
        }
        this.clientSidePeerHandler = handler;
        endpointstats.getActiveConnections().incrementAndGet();
    }

    private void connectionDeactivated() {
        if (active.compareAndSet(true, false)) {
            endpointstats.getActiveConnections().decrementAndGet();
            detachFromClient();
        } else {
            LOG.log(Level.SEVERE, "connectionDeactivated on a non active connection! {0}", this);
        }

    }

    private class ReadEndpointResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            contextToEndpoint = ctx;
            super.handlerAdded(ctx);
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (clientSidePeerHandler == null) {
                LOG.log(Level.INFO, "swallow content {0}: {1}, disconnected client", new Object[]{msg.getClass(), msg});
                return;
            }
            if (msg instanceof HttpContent) {
                HttpContent f = (HttpContent) msg;
//                LOG.log(Level.INFO, "proxying HttpContent {0}: {1}", new Object[]{msg.getClass(), msg});
                clientSidePeerHandler.receivedFromRemote(f.copy());
            } else if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse f = (DefaultHttpResponse) msg;
//                LOG.log(Level.INFO, "proxying DefaultHttpResponse {0}: headers: {1}", new Object[]{msg.getClass(), msg});
//                f.headers().forEach((entry) -> {
//                    LOG.log(Level.INFO, "proxying header " + entry.getKey() + ": " + entry.getValue());
//                });
                clientSidePeerHandler.receivedFromRemote(new DefaultHttpResponse(f.protocolVersion(),
                    f.status(), f.headers()));
            } else {
                LOG.log(Level.SEVERE, "unknown message type " + msg.getClass(), new Exception("unknown message type " + msg.getClass())
                    .fillInStackTrace());
            }

        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//            LOG.log(Level.INFO, "channelRead " + msg + ", clientSidePeerHandler:" + clientSidePeerHandler);
            super.channelRead(ctx, msg); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean acceptInboundMessage(Object msg) throws Exception {
//            LOG.log(Level.INFO, "acceptInboundMessage " + msg);
            return super.acceptInboundMessage(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
//            LOG.log(Level.INFO, "channelReadComplete {0}", ctx);
            if (clientSidePeerHandler != null) {
                clientSidePeerHandler.readCompletedFromRemote();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.SEVERE, "bad error", cause);
            valid = false;
            ctx.close();
            connectionDeactivated();
        }

    }

    @Override
    public String toString() {
        return "ProxyHttpClientConnection{id=" + id + ", channel=" + channelToEndpoint + ", key=" + key + ", valid=" + valid + ", closed=" + closed + '}';
    }

}
