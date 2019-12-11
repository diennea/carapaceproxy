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
import io.netty.channel.EventLoopGroup;
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
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.EndpointConnection;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.utils.PrometheusUtils;

public class EndpointConnectionImpl implements EndpointConnection {

    private static final Logger LOG = Logger.getLogger(EndpointConnectionImpl.class.getName());

    private static final AtomicLong IDGENERATOR = new AtomicLong();

    private static final String METRIC_LABEL_RESULT = "result";
    private static final String METRIC_LABEL_HOST = "host";
    private static final String METRIC_LABEL_RESULT_SUCCESS = "success";
    private static final String METRIC_LABEL_RESULT_FAILURE = "failure";

    private final long id = IDGENERATOR.incrementAndGet();
    private final ConnectionsManagerImpl parent;
    private final EndpointKey key;
    private final EndpointStats endpointstats;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();

    private final Channel channelToEndpoint;

    private AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private volatile boolean forcedInvalid = false;
    private volatile RequestHandler clientSidePeerHandler;

    // stats
    private static final Summary CONNECTION_STATS_SUMMARY = PrometheusUtils.createSummary("backends", "connection_time_ns",
            "backend connections", METRIC_LABEL_HOST, METRIC_LABEL_RESULT).register();
    private static final Gauge OPEN_CONNECTIONS_GAUGE = PrometheusUtils.createGauge("backends", "open_connections",
            "currently open backend connections", METRIC_LABEL_HOST).register();
    private static final Gauge ACTIVE_CONNECTIONS_GAUGE = PrometheusUtils.createGauge("backends", "active_connections",
            "currently active backend connections", METRIC_LABEL_HOST).register();
    private static final Counter TOTAL_REQUESTS_COUNTER = PrometheusUtils.createCounter("backends", "sent_requests_total",
            "sent requests", METRIC_LABEL_HOST).register();

    private final Gauge.Child openConnectionsStats;
    private final Gauge.Child activeConnectionsStats;
    private final Counter.Child requestsStats;

    private static enum ConnectionState {
        IDLE,
        REQUEST_SENT,
        RELEASABLE,
        DELAYED_RELEASE
    }

    /**
     * Creates and return always a "CONNECTED" connection. This constructor will block until the backend is connected
     *
     * @param key
     * @param parent
     * @param endpointstats
     * @throws IOException
     */
    public EndpointConnectionImpl(EndpointKey key, ConnectionsManagerImpl parent, EndpointStats endpointstats) throws IOException {
        this.key = key;
        this.parent = parent;
        this.forcedInvalid = false;
        this.endpointstats = endpointstats;
        activityDone();

        EventLoopGroup eventLoopForOutboundConnections = parent.getEventLoopForOutboundConnections();
        if (eventLoopForOutboundConnections.isShuttingDown()) {
            throw new IOException("eventLoopForOutboundConnections "
                    + eventLoopForOutboundConnections + " has been shutdown, cannot connect to " + key);
        }
        String labelHost = key.getHost() + "_" + key.getPort();
        this.openConnectionsStats = OPEN_CONNECTIONS_GAUGE.labels(labelHost);
        this.activeConnectionsStats = ACTIVE_CONNECTIONS_GAUGE.labels(labelHost);
        this.requestsStats = TOTAL_REQUESTS_COUNTER.labels(labelHost);

        final long startTime = System.nanoTime();
        Bootstrap b = new Bootstrap();

        b.group(eventLoopForOutboundConnections)
                .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, parent.getConnectTimeout())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(parent.getIdleTimeout() / 2));
                        ch.pipeline().addLast("writeTimeoutHandler", new WriteTimeoutHandler(parent.getIdleTimeout() / 2));
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
                CONNECTION_STATS_SUMMARY.labels(labelHost, METRIC_LABEL_RESULT_SUCCESS)
                        .observe(System.nanoTime() - startTime);
            } else {
                CONNECTION_STATS_SUMMARY.labels(labelHost, METRIC_LABEL_RESULT_FAILURE)
                        .observe(System.nanoTime() - startTime);
                LOG.log(Level.INFO, "connect failed to " + key, future.cause());
                parent.backendHealthManager.reportBackendUnreachable(key.getHostPort(),
                        System.currentTimeMillis(), "connection failed");
            }
        });
        try {
            connectFuture.get(parent.getConnectTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
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
        if (forcedInvalid) {
            throw new IOException("Cannot connect to " + key+" (already invalidated)");
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
        if (assertNotInEndpointEventLoop(clientSidePeerHandler)) {
            return;
        }
        checkHandler(null);
        if (!channelToEndpoint.isOpen() || forcedInvalid) {
            LOG.log(Level.SEVERE, "sendRequest " + request.getClass() + " failed, choosen connection is not valid");
            clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, new Exception("no more connected").fillInStackTrace());
            return;
        }
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("this connection is already active!");
        }
        if (!changeStateTo(ConnectionState.REQUEST_SENT, ConnectionState.IDLE)) {
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
                        changeStateTo(ConnectionState.RELEASABLE, ConnectionState.REQUEST_SENT);
                        _clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                        invalidate();
                    }
                });
    }

    private boolean assertNotInEndpointEventLoop(RequestHandler clientSidePeerHandler1) {
        if (channelToEndpoint.eventLoop().inEventLoop()) {
            LOG.log(Level.SEVERE, "Bad thread {0} for {1} with {2}", new Object[]{Thread.currentThread().getName(), this, clientSidePeerHandler1});
            return true;
        }
        return false;
    }

    @Override
    public void sendChunk(HttpContent msg, RequestHandler clientSidePeerHandler) {
        if (assertNotInEndpointEventLoop(clientSidePeerHandler)) {
            return;
        }
        checkHandler(clientSidePeerHandler);
        if (!channelToEndpoint.isOpen() || forcedInvalid) {
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
                        changeStateTo(ConnectionState.RELEASABLE, ConnectionState.REQUEST_SENT);
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
        if (assertNotInEndpointEventLoop(clientSidePeerHandler)) {
            return;
        }
        checkHandler(clientSidePeerHandler);
        if (!channelToEndpoint.isOpen() || forcedInvalid) {
            invalidate();
            LOG.log(Level.SEVERE, "continueRequest {0} to {1} . skip to invalid connection to endpoint {2}", new Object[]{msg, channelToEndpoint, this.key});
            changeStateTo(ConnectionState.RELEASABLE, ConnectionState.REQUEST_SENT);
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
                            changeStateTo(ConnectionState.RELEASABLE, ConnectionState.REQUEST_SENT, ConnectionState.DELAYED_RELEASE);
                            clientSidePeerHandler.errorSendingRequest(EndpointConnectionImpl.this, future.cause());
                            invalidate();
                        } else {
                            boolean recover = false;
                            if (!changeStateTo(ConnectionState.RELEASABLE, ConnectionState.REQUEST_SENT)) {
                                LOG.log(Level.SEVERE, "sendLastHttpContent finished without " + ConnectionState.REQUEST_SENT + " state: recovery");
                                recover = true;
                            }
                            clientSidePeerHandler.lastHttpContentSent();
                            if (recover) {
                                if (changeStateTo(ConnectionState.RELEASABLE, ConnectionState.DELAYED_RELEASE)) {
                                    LOG.log(Level.INFO, "recovering DELAYED_RELEASE " + this);
                                    release(false, clientSidePeerHandler);
                                }
                            }
                        }
                });

    }

    private boolean changeStateTo(ConnectionState newValue, ConnectionState ... expected) {
        if (state.accumulateAndGet(newValue, (prevValue, nValue)-> {
            boolean ok = false;
            for (ConnectionState s : expected) {
                if (prevValue == s) {
                    ok = true;
                }
            }
            return ok ? nValue : prevValue;
        }) != newValue) {
            LOG.log(Level.INFO, "{0} Cannot change state (expected {1}) to {2}", new Object[]{this, Arrays.toString(expected), newValue});
            return false;
        } else {
            return true;
        }
    }

    private void executeInEndpointConnectionEventLoop(Runnable r) {
        if (channelToEndpoint.eventLoop().inEventLoop()) {
            r.run();
        } else {
            channelToEndpoint.eventLoop().submit(r);
        }
    }

    @Override
    public void release(boolean close, RequestHandler clientSidePeerHandler) {
        // this method can be called from RequestHandler eventLoop and from EndpointConnection eventloop
        executeInEndpointConnectionEventLoop(() -> {
            if (changeStateTo(ConnectionState.IDLE, ConnectionState.RELEASABLE, ConnectionState.DELAYED_RELEASE)) {
                LOG.log(Level.FINE, "release {0} {1}", new Object[]{close, this});
                checkHandler(clientSidePeerHandler);
                connectionDeactivated();
                if (close) {
                    destroy();
                }
                parent.returnConnection(this);
            } else {
                LOG.log(Level.SEVERE, "cannot release now " + this);
                changeStateTo(ConnectionState.DELAYED_RELEASE, ConnectionState.REQUEST_SENT);
            }
        });
    }

    void destroy() {
        invalidate();
        if (!closed.compareAndSet(false, true)) {
            return;

        }
        // note: close is async, we are not waiting for real close
        channelToEndpoint.close();
    }

    String validate() {
        long delta = (System.currentTimeMillis() - endpointstats.getLastActivity().longValue());
        boolean isOpen= channelToEndpoint.isOpen();
        boolean ok = !forcedInvalid
                && (delta <= parent.getIdleTimeout())
                && channelToEndpoint.isOpen();
        if (ok) {
            return null;
        } else {
            return "NOT VALID (delta " + delta + "/" + parent.getIdleTimeout()
                    + ", open " + isOpen + ", forcedInvalid=" + forcedInvalid + ")";
        }
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
        return "{cid=" + id + ", " + state + ", channel=" + channelToEndpoint + ", key=" + key + ", forcedInvalid=" + forcedInvalid + ", closed=" + closed + '}';
    }

    private void checkHandler(RequestHandler handler) throws IllegalStateException {
        if (this.clientSidePeerHandler != handler) {
            throw new IllegalStateException("connection is bound to " + this.clientSidePeerHandler + " cannot be managed by " + handler);
        }
    }

    private void activityDone() {
        endpointstats.getLastActivity().set(System.currentTimeMillis());
    }

    private void invalidate() {
        forcedInvalid = true;
    }

    @Override
    public EndpointKey getKey() {
        return key;
    }

}
