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
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.ProxiedConnectionHandler;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;

public class ProxyHttpClientConnection implements EndpointConnection {

    private final ConnectionsManagerImpl parent;
    private final EndpointKey key;
    private final EndpointStats endpointstats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();

    private volatile Channel channelToEndpoint;
    private volatile ChannelHandlerContext contextToEndpoint;
    private volatile boolean valid;
    private volatile ProxiedConnectionHandler clientSidePeerHandler;
    private volatile ChannelHandlerContext currentPeerChannel;

    public ProxyHttpClientConnection(EndpointKey key, ConnectionsManagerImpl parent, EndpointStats endpointstats) {
        this.key = key;
        this.parent = parent;
        this.valid = true;
        this.endpointstats = endpointstats;
    }

    private ChannelFuture ensureConnected() {
        if (channelToEndpoint != null) {
            LOG.log(Level.INFO, "Already connected to {0}, channel {1}, pipeline {2}", new Object[]{key, channelToEndpoint, channelToEndpoint.pipeline()});
            channelToEndpoint.pipeline().remove(HttpClientCodec.class);
            channelToEndpoint.pipeline().addFirst("client-codec", new HttpClientCodec());
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
            endpointstats.getTotalConnections().incrementAndGet();
            endpointstats.getOpenConnections().incrementAndGet();
        });
        channelToEndpoint = connectFuture.channel();
        return connectFuture;

    }

    @Override
    public void sendRequest(HttpRequest request, ProxiedConnectionHandler clientSidePeerHandler, ChannelHandlerContext peerChannel) {

        activateConnection(clientSidePeerHandler, peerChannel);
        ChannelFuture afterConnect = ensureConnected();
        afterConnect.addListener((Future<Void> future) -> {
            endpointstats.getTotalRequests().incrementAndGet();
            LOG.info("sendRequest " + request.getClass() + " to channelToEndpoint " + channelToEndpoint);
            channelToEndpoint.writeAndFlush(request).addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    if (!future.isSuccess()) {
                        LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed", future.cause());
                        clientSidePeerHandler.errorSendingRequest(ProxyHttpClientConnection.this, peerChannel);
                    } else {
                        LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " completed");
                    }
                }
            });
        });
        try {
            afterConnect.sync();
        } catch (InterruptedException err) {
            LOG.log(Level.SEVERE, "sendRequest interrupted during connection", err);
            clientSidePeerHandler.errorSendingRequest(ProxyHttpClientConnection.this, peerChannel);
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

    private static final Logger LOG = Logger.getLogger(ProxyHttpClientConnection.class.getName());

    @Override
    public void sendLastHttpContent(LastHttpContent msg) {
        System.out.println("sendLastHttpContent to " + contextToEndpoint);
        System.out.println("sendLastHttpContent to " + contextToEndpoint.pipeline());
        contextToEndpoint.writeAndFlush(msg).addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                if (!future.isSuccess()) {
                    LOG.log(Level.INFO, "sendLastHttpContent failed " + msg, future.cause());
                } else {
                    LOG.log(Level.INFO, "sendLastHttpContent success");
                }
            }

        });
    }

    private void detachFromSourceConnection() {
        clientSidePeerHandler = null;
        currentPeerChannel = null;
    }

    void destroy() {

        if (!closed.compareAndSet(false, true)) {
            return;
        }

        LOG.log(Level.INFO, "destroy {0}", this);
        valid = false;
        if (channelToEndpoint != null) {
            channelToEndpoint.close().addListener(new GenericFutureListener() {
                @Override
                public void operationComplete(Future future) throws Exception {
                    endpointstats.getOpenConnections().decrementAndGet();
                }
            });
            channelToEndpoint = null;
        }
    }

    boolean isValid() {
        return valid;
    }

    private void activateConnection(ProxiedConnectionHandler handler, ChannelHandlerContext peerChannel) {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("this connection is already active!");
        }
        this.clientSidePeerHandler = handler;
        this.currentPeerChannel = peerChannel;
        endpointstats.getActiveConnections().incrementAndGet();
    }

    private void connectionDeactivated() {
        if (active.compareAndSet(true, false)) {
            endpointstats.getActiveConnections().decrementAndGet();
            detachFromSourceConnection();
        } else {
            LOG.log(Level.SEVERE, "connectionDeactivated on a non active connection! {0}", this);
        }

    }

    private class ReadEndpointResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            LOG.log(Level.INFO, "handlerAdded " + ctx);
            super.handlerAdded(ctx);
            contextToEndpoint = ctx;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            LOG.log(Level.INFO, "channelRead0 {0} {1}", new Object[]{msg.getClass(), msg});
            if (msg instanceof HttpContent) {
                HttpContent f = (HttpContent) msg;
                LOG.log(Level.INFO, "proxying HttpContent {0}: {1}", new Object[]{msg.getClass(), msg});
                clientSidePeerHandler.receivedFromRemote(f.copy(), currentPeerChannel);
            } else if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse f = (DefaultHttpResponse) msg;
                LOG.log(Level.INFO, "proxying DefaultHttpResponse {0}: headers: {1}", new Object[]{msg.getClass(), msg});
                f.headers().forEach((entry) -> {
                    LOG.log(Level.INFO, "proxying header " + entry.getKey() + ": " + entry.getValue());
                });
                clientSidePeerHandler.receivedFromRemote(new DefaultHttpResponse(f.protocolVersion(),
                    f.status(), f.headers()), currentPeerChannel);
            } else {
                LOG.log(Level.SEVERE, "unknown mesasge type " + msg.getClass(), new Exception("unknown mesasge type " + msg.getClass())
                    .fillInStackTrace());
            }

        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            super.channelRead(ctx, msg); //To change body of generated methods, choose Tools | Templates.
            LOG.log(Level.INFO, "channelRead " + msg + ", clientSidePeerHandler:" + clientSidePeerHandler);
        }

        @Override
        public boolean acceptInboundMessage(Object msg) throws Exception {
            LOG.log(Level.INFO, "acceptInboundMessage " + msg);
            return super.acceptInboundMessage(msg); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            LOG.log(Level.INFO, "channelReadComplete {0}", ctx);
            if (clientSidePeerHandler != null) {
                clientSidePeerHandler.readCompletedFromRemote(currentPeerChannel);
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
        return "ProxyHttpClientConnection{" + "channel=" + channelToEndpoint + ", key=" + key + ", valid=" + valid + ", closed=" + closed + '}';
    }

}
