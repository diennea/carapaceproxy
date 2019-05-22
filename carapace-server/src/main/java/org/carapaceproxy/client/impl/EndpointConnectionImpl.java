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
package org.carapaceproxy.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.EndpointConnection;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.RequestHandler;

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

    private AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private volatile boolean valid;
    private volatile RequestHandler clientSidePeerHandler;

    // stats
    private final StatsLogger endpointStatsLogger;
    private final OpStatsLogger connectionStats;
    private final Counter openConnectionsStats;
    private final Counter activeConnectionsStats;
    private final Counter requestsStats;

    private static enum ConnectionState {
        IDLE,
        REQUEST_SENT,
        RELEASABLE
    }

    /**
     * Creates and return always a "CONNECTED" connection. This constructor will
     * block until the backend is connected
     *
     * @param key
     * @param parent
     * @param endpointstats
     * @throws IOException
     */
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
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
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
                LOG.log(Level.INFO, "connect failed to " + key, future.cause());
                parent.backendHealthManager.reportBackendUnreachable(key.getHostPort(), System.currentTimeMillis(), "connection failed");
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
                    endpointstats.getOpenConnections().decrementAndGet();
                    openConnectionsStats.dec();
                });

    }

    @Override
    public void sendRequest(HttpRequest request, RequestHandler clientSidePeerHandler) {
        checkHandler(null);
        if (!channelToEndpoint.isOpen() || !valid) {
            LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed, choosen connection is not valid");
            clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, new Exception("no more connected").fillInStackTrace());
            return;
        }
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("this connection is already active!");
        }
        if (!state.compareAndSet(ConnectionState.IDLE, ConnectionState.REQUEST_SENT)) {
            LOG.log(Level.SEVERE, "bad status ! " + this + ", handler is " + clientSidePeerHandler);
            throw new IllegalStateException("bad status ! " + this);
        }

        this.clientSidePeerHandler = clientSidePeerHandler;
        endpointstats.getActiveConnections().incrementAndGet();
        activeConnectionsStats.inc();
        endpointstats.getTotalRequests().incrementAndGet();
        requestsStats.inc();

        parent.registerPendingRequest(clientSidePeerHandler);

        // we are considering here (before writeAndFlush) that the
        // request has been sent, because 'after' the writeAndFlush (on the listener)
        // it will be too late and the response from the server
        // may have already been received !
        clientSidePeerHandler.messageSentToBackend(EndpointConnectionImpl.this);
        activityDone();
        channelToEndpoint
                .writeAndFlush(request)
                .addListener((Future<? super Void> future) -> {
                    // BEWARE THAT THE RESPONSE MAY ALREADY HAVE BEEN
                    // RECEIVED
                    RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
                    if (!future.isSuccess()) {
                        LOG.log(Level.INFO, this + " sendRequest " + request.getClass() + " failed", future.cause());
                        state.compareAndSet(ConnectionState.REQUEST_SENT, ConnectionState.RELEASABLE);
                        _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                        invalidate();
                    }
                });
    }

    @Override
    public void sendChunk(HttpContent msg, RequestHandler clientSidePeerHandler) {
        checkHandler(clientSidePeerHandler);
        if (!channelToEndpoint.isOpen() || !valid) {
            invalidate();
            LOG.log(Level.SEVERE, "continueRequest {0} to {1} . skip to invalid connection to endpoint {2}", new Object[]{msg, channelToEndpoint, this.key});
            clientSidePeerHandler.errorSendingRequest(this, new IOException("endpoint died"));
            return;
        }

        // we are considering here (before writeAndFlush) that the
        // chunk has been sent, because 'after' the writeAndFlush (on the listener)
        // it will be too late and the response from the server
        // may have already been received !
        clientSidePeerHandler.messageSentToBackend(EndpointConnectionImpl.this);
        activityDone();
        channelToEndpoint
                .writeAndFlush(msg)
                .addListener((Future<? super Void> future) -> {
                    if (!future.isSuccess()) {
                        state.compareAndSet(ConnectionState.REQUEST_SENT, ConnectionState.RELEASABLE);
                        boolean done = clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                        if (done) {
                            LOG.log(Level.SEVERE, this + " continueRequest " + msg.getClass() + " failed", future.cause());
                            invalidate();
                        }
                    }
                });
    }

    @Override
    public void sendLastHttpContent(LastHttpContent msg, RequestHandler clientSidePeerHandler) {
        checkHandler(clientSidePeerHandler);
        if (!channelToEndpoint.isOpen() || !valid) {
            invalidate();
            LOG.log(Level.SEVERE, "continueRequest {0} to {1} . skip to invalid connection to endpoint {2}", new Object[]{msg, channelToEndpoint, this.key});
            state.compareAndSet(ConnectionState.REQUEST_SENT, ConnectionState.RELEASABLE);
            clientSidePeerHandler.errorSendingRequest(this, new IOException("endpoint died"));
            return;
        }
        // we are considering here (before writeAndFlush) that the
        // chunk has been sent, because 'after' the writeAndFlush (on the listener)
        // it will be too late and the response from the server
        // may have already been received !
        activityDone();
        channelToEndpoint
                .writeAndFlush(msg)
                .addListener((Future<? super Void> future) -> {
                    if (!future.isSuccess()) {
                        LOG.log(Level.INFO, "sendLastHttpContent failed " + msg, future.cause());
                        state.compareAndSet(ConnectionState.REQUEST_SENT, ConnectionState.RELEASABLE);
                        clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                        invalidate();
                    } else {
                        if (!state.compareAndSet(ConnectionState.REQUEST_SENT, ConnectionState.RELEASABLE)) {
                            // this may be a bug
                            LOG.log(Level.SEVERE, "sendLastHttpContent finished without " + ConnectionState.REQUEST_SENT + " state");
                        }
                        clientSidePeerHandler.lastHttpContentSent();
                    }
                });

    }

    @Override
    public void release(boolean close, RequestHandler clientSidePeerHandler) {
        if (!state.compareAndSet(ConnectionState.RELEASABLE, ConnectionState.IDLE)) {
            LOG.log(Level.SEVERE, "cannot release now " + this);
            return;
        }
//        LOG.log(Level.SEVERE, "release " + close + " " + this);
        checkHandler(clientSidePeerHandler);
        connectionDeactivated();

        if (close) {
            destroy();
        }
        parent.returnConnection(this);

    }

    void destroy() {
        invalidate();
        if (!closed.compareAndSet(false, true)) {
            return;

        }
        // note: close is async, we are not waiting for real close
        channelToEndpoint.close();
    }

    boolean isValid() {
        return valid
                && (System.currentTimeMillis() - endpointstats.getLastActivity().longValue() <= idleTimeout)
                && channelToEndpoint.isOpen();
    }

    private void connectionDeactivated() {
        if (active.compareAndSet(true, false)) {
            endpointstats.getActiveConnections().decrementAndGet();
            activeConnectionsStats.dec();
            parent.unregisterPendingRequest(clientSidePeerHandler);
            clientSidePeerHandler = null;
        } else {
            LOG.log(Level.SEVERE, "connectionDeactivated on a non active connection! {0}", this);
        }

    }

    private class ReadEndpointResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler == null) {
                LOG.log(Level.INFO, "swallow content {0}: {1}, disconnected client", new Object[]{msg.getClass(), msg});
                return;
            }
            if (msg instanceof HttpContent) {
                HttpContent f = (HttpContent) msg;
                _clientSidePeerHandler.receivedFromRemote(f.copy(),
                        EndpointConnectionImpl.this);
            } else if (msg instanceof DefaultHttpResponse) {
                DefaultHttpResponse f = (DefaultHttpResponse) msg;
                // DefaultHttpResponse has no "copy" method
                _clientSidePeerHandler.receivedFromRemote(
                        new DefaultHttpResponse(f.protocolVersion(), f.status(), f.headers()),
                        EndpointConnectionImpl.this);
            } else {
                LOG.log(Level.SEVERE, "unknown message type " + msg.getClass() + ": " + msg);
            }

        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            RequestHandler _clientSidePeerHandler = clientSidePeerHandler;
            if (_clientSidePeerHandler != null) {
                _clientSidePeerHandler.readCompletedFromRemote();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.SEVERE, "I/O error on endpoint " + key, cause);
            parent.backendHealthManager.reportBackendUnreachable(key.getHostPort(), System.currentTimeMillis(), "I/O error: " + cause);
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
        return "{id=" + id + ", " + state + ", channel=" + channelToEndpoint + ", key=" + key + ", valid=" + valid + ", closed=" + closed + '}';
    }

    private void checkHandler(RequestHandler clientSidePeerHandler1) throws IllegalStateException {
        if (this.clientSidePeerHandler != clientSidePeerHandler1) {
            throw new IllegalStateException("connection is bound to " + this.clientSidePeerHandler + " cannot be managed by " + clientSidePeerHandler1);
        }
    }

    private void activityDone() {
        endpointstats.getLastActivity().set(System.currentTimeMillis());
    }

    private void invalidate() {
        valid = false;
    }

    @Override
    public EndpointKey getKey() {
        return key;
    }

    @Override
    public void setIdleTimeout(int timeout) {
        this.idleTimeout = timeout;
    }

}
