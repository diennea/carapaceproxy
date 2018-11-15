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
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.RequestHandler;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;

public class EndpointConnectionImpl implements EndpointConnection {

    private static final Logger LOG = Logger.getLogger(EndpointConnectionImpl.class.getName());

    private static final AtomicLong IDGENERATOR = new AtomicLong();

    private final long id = IDGENERATOR.incrementAndGet();
    private final ConnectionsManagerImpl parent;
    private final EndpointKey key;
    private final EndpointStats endpointstats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private int idleTimeout = 500_000;

    private final Channel channelToEndpoint;
    private volatile ChannelHandlerContext contextToEndpoint;
    private volatile boolean valid;
    private volatile RequestHandler clientSidePeerHandler;
    private final StatsLogger endpointStatsLogger;
    private final OpStatsLogger connectionStats;
    private final Counter openConnectionsStats;
    private final Counter activeConnectionsStats;
    private final Counter requestsStats;

    @Override
    public void setIdleTimeout(int timeout) {
        this.idleTimeout = timeout;
    }

    public EndpointConnectionImpl(EndpointKey key, ConnectionsManagerImpl parent, EndpointStats endpointstats) throws IOException {
        this.endpointStatsLogger = parent.mainLogger.scope(key.getHost() + "_" + key.getPort());
        this.connectionStats = endpointStatsLogger.getOpStatsLogger("connections");
        this.openConnectionsStats = endpointStatsLogger.getCounter("openconnections");
        this.activeConnectionsStats = endpointStatsLogger.getCounter("activeconnections");
        this.requestsStats = endpointStatsLogger.getCounter("requests");
        this.key = key;
        this.parent = parent;
        this.valid = true;
        this.endpointstats = endpointstats;
        activityDone();

        long now = System.nanoTime();
        Bootstrap b = new Bootstrap();
        b.group(parent.getGroup())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(idleTimeout / 2));
                        ch.pipeline().addLast("writeTimeoutHandler", new WriteTimeoutHandler(idleTimeout / 2));
                        ch.pipeline().addLast("client-codec", new HttpClientCodec());
                        ch.pipeline().addLast(new ReadEndpointResponseHandler());
                    }
                });

        final ChannelFuture connectFuture = b.connect(key.getHost(), key.getPort());

        connectFuture.addListener((Future<Void> future) -> {
            if (future.isSuccess()) {
                endpointstats.getTotalConnections().incrementAndGet();
                endpointstats.getOpenConnections().incrementAndGet();
                openConnectionsStats.inc();
                connectionStats.registerSuccessfulEvent(System.nanoTime() - now, TimeUnit.NANOSECONDS);
            } else {
                connectionStats.registerFailedEvent(System.nanoTime() - now, TimeUnit.NANOSECONDS);
                invalidate();
                LOG.log(Level.INFO, "connect failed to " + key, future.cause());
                parent.backendHealthManager.reportBackendUnreachable(key.toBackendId(), System.currentTimeMillis(), "connection failed");
            }
        });
        try {
            connectFuture.get(parent.getConnectTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException err) {
            throw new IOException(err);
        } catch (ExecutionException err) {
            LOG.log(Level.INFO, "cannot create a valid connection to " + key, err.getCause());
            if (err.getCause() instanceof IOException) {
                throw (IOException) err.getCause();
            } else {
                throw new IOException(err.getCause());
            }
        } catch (TimeoutException err) {
            LOG.log(Level.INFO, "timed out creating a valid connection to " + key, err);
            throw new IOException(err);
        }
        if (!valid) {
            throw new IOException("Cannot connect to " + key);
        }

        channelToEndpoint = connectFuture.channel();
        channelToEndpoint
                .closeFuture()
                .addListener((Future<? super Void> future) -> {
                    LOG.log(Level.FINE, "channel closed to {0}", key);
                    invalidate();
                });

    }

    private void activityDone() {
        endpointstats.getLastActivity().set(System.currentTimeMillis());
    }

    private void invalidate() {
        valid = false;
    }

    @Override
    public void sendRequest(HttpRequest request, RequestHandler clientSidePeerHandler) {
        activateConnection(clientSidePeerHandler);
        endpointstats.getTotalRequests().incrementAndGet();
        requestsStats.inc();

        if (!channelToEndpoint.isOpen()) {
            LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed, choosen connection is not valid");
            invalidate();
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler != null) {
                _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, new Exception("no more connected").fillInStackTrace());
            }
            return;
        }
        parent.registerPendingRequest(clientSidePeerHandler);

        channelToEndpoint
                .writeAndFlush(request)
                .addListener((Future<? super Void> future) -> {
                    activityDone();
                    RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
                    if (!future.isSuccess()) {
                        LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed", future.cause());
                        if (_clientSidePeerHandler != null) {
                            _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                        }
                        // send to unreachable state if we are not able to send a request on the wire
                        parent.backendHealthManager.reportBackendUnreachable(key.toBackendId(), System.currentTimeMillis(), "write failed (" + future.cause() + ")");
                    } else {
                        if (_clientSidePeerHandler != null) {
                            _clientSidePeerHandler.messageSentToBackend(EndpointConnectionImpl.this);
                        }
                        // back to reachable state at first request sent to the backend
                        parent.backendHealthManager.reportBackendReachable(key.toBackendId());
                    }
                    if (!channelToEndpoint.isOpen()) {
                        invalidate();
                    }
                });
    }

    @Override
    public void continueRequest(HttpContent msg) {
        Channel _channelToEndpoint = channelToEndpoint;
        if (_channelToEndpoint == null || !_channelToEndpoint.isOpen()) {
            invalidate();
        }
        if (!valid) {
            LOG.log(Level.SEVERE, "continueRequest {0} to {1} . skip to invalid connection to endpoint {2}", new Object[]{msg, contextToEndpoint, this.key});
            clientSidePeerHandler.errorSendingRequest(this, new IOException("endpoint died"));
            return;
        }
        _channelToEndpoint.writeAndFlush(msg).addListener((Future<? super Void> future) -> {
            activityDone();
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (!future.isSuccess()) {
                LOG.log(Level.SEVERE, "continueRequest " + msg.getClass() + " failed", future.cause());
                if (_clientSidePeerHandler != null) {
                    _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                }
                invalidate();
            } else {
                if (_clientSidePeerHandler != null) {
                    _clientSidePeerHandler.messageSentToBackend(EndpointConnectionImpl.this);
                }
            }
        });
    }

    @Override
    public void sendLastHttpContent(LastHttpContent msg, RequestHandler clientSidePeerHandler) {
        Channel _channelToEndpoint = channelToEndpoint;
        if (_channelToEndpoint == null || !_channelToEndpoint.isOpen()) {
            invalidate();
        }
        if (!valid) {
            LOG.log(Level.SEVERE, "sendLastHttpContent {0} to {1} . skip to invalid connection to endpoint {2}", new Object[]{msg, contextToEndpoint, this.key});
            clientSidePeerHandler.errorSendingRequest(this, new IOException("endpoint died"));
            return;
        }
        activityDone();
        ChannelHandlerContext _contextToEndpoint = contextToEndpoint;
        _contextToEndpoint.writeAndFlush(msg).addListener((Future<? super Void> future) -> {
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (!future.isSuccess()) {
                LOG.log(Level.INFO, "sendLastHttpContent failed " + msg, future.cause());
                if (_clientSidePeerHandler != null) {
                    _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                }
            } else {
                if (_clientSidePeerHandler != null) {
                    _clientSidePeerHandler.lastHttpContentSent();
                }
            }
//            LOG.log(Level.SEVERE, "sendLastHttpContent finished, now " + _contextToEndpoint + " is open ? " + _contextToEndpoint.channel().isOpen());
            if (!_contextToEndpoint.channel().isOpen()) {
                invalidate();

            }
        });

    }

    private void detachFromClient() {
//        LOG.info("detachFromClient");        
        parent.unregisterPendingRequest(clientSidePeerHandler);
        clientSidePeerHandler = null;

    }

    void destroy() {

        if (!closed.compareAndSet(false, true)) {
            return;

        }

//        LOG.log(Level.INFO, "destroy {0}", this);
        invalidate();
        Channel _channel = channelToEndpoint;
        if (_channel != null) {
            _channel.close().addListener((future) -> {
                //                    LOG.log(Level.INFO, "connection id " + id + " to " + key + " closed now");
                endpointstats.getOpenConnections().decrementAndGet();
                openConnectionsStats.dec();
            });
            _channel = null;
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
        activeConnectionsStats.inc();
    }

    private void connectionDeactivated() {
        if (active.compareAndSet(true, false)) {
            endpointstats.getActiveConnections().decrementAndGet();
            activeConnectionsStats.dec();
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
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler == null) {
                LOG.log(Level.INFO, "swallow content {0}: {1}, disconnected client", new Object[]{msg.getClass(), msg});
                return;
            }
            if (msg instanceof HttpContent) {
                HttpContent f = (HttpContent) msg;
//                LOG.log(Level.INFO, "proxying HttpContent {0}: {1}", new Object[]{msg.getClass(), msg});
                _clientSidePeerHandler.receivedFromRemote(f.copy());
            } else if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse f = (DefaultHttpResponse) msg;
//                LOG.log(Level.INFO, "proxying DefaultHttpResponse {0}: headers: {1}", new Object[]{msg.getClass(), msg});
//                f.headers().forEach((entry) -> {
//                    LOG.log(Level.INFO, "proxying header " + entry.getKey() + ": " + entry.getValue());
//                });
                _clientSidePeerHandler.receivedFromRemote(new DefaultHttpResponse(f.protocolVersion(),
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
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler != null) {
                _clientSidePeerHandler.readCompletedFromRemote();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.SEVERE, "I/O error on endpoint " + key, cause);
            parent.backendHealthManager.reportBackendUnreachable(key.toBackendId(), System.currentTimeMillis(), "I/O error: " + cause);
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler != null) {
                _clientSidePeerHandler.badErrorOnRemote(cause);
            }
            invalidate();
            ctx.close();
            connectionDeactivated();
        }

    }

    @Override
    public String toString() {
        return "{id=" + id + ", channel=" + channelToEndpoint + ", key=" + key + ", valid=" + valid + ", closed=" + closed + '}';
    }

    @Override
    public void release(boolean close) {
        connectionDeactivated();

        if (close) {
            destroy();
        }
        parent.returnConnection(this);

    }

    @Override
    public EndpointKey getKey() {
        return key;
    }

}
