/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server;

import org.carapaceproxy.client.ConnectionsManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.EndpointMapper;
import org.carapaceproxy.client.impl.EndpointConnectionImpl;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.PrometheusUtils;

public class ClientConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOG = Logger.getLogger(ClientConnectionHandler.class.getName());
    private static final AtomicLong idgenerator = new AtomicLong();
    private final long id = idgenerator.incrementAndGet();

    private final static AtomicLong requestIdGenerator = new AtomicLong();

    private static final Counter TOTAL_REQUESTS_COUNTER_PER_LISTENER = PrometheusUtils.createCounter("listeners", "requests_total",
            "total requests", "listener").register();
    private static final Gauge RUNNING_REQUESTS_GAUGE = PrometheusUtils.createGauge("listeners", "running_requests",
            "running requests").register();

    private final Counter.Child totalRequests;

    final EndpointMapper mapper;
    final BackendHealthManager backendHealthManager;
    final ConnectionsManager connectionsManager;
    final List<RequestFilter> filters;
    final SocketAddress clientAddress;
    final ContentsCache cache;
    final StaticContentsManager staticContentsManager;
    final RequestsLogger requestsLogger;
    final FullHttpMessageLogger fullHttpMessageLogger;
    final long connectionStartsTs;
    volatile boolean keepAlive = true;
    volatile boolean refuseOtherRequests;
    private final AtomicReference<RequestHandler> pendingRequest = new AtomicReference<>();
    private volatile boolean requestRunning;
    final Runnable onClientDisconnected;
    private final String listenerHost;
    private final int listenerPort;
    private final boolean secure; // connection bind to https
    private String sslProtocol;
    private String cipherSuite;
    final SocketAddress serverAddress;

    public ClientConnectionHandler(
            EndpointMapper mapper,
            ConnectionsManager connectionsManager,
            List<RequestFilter> filters,
            ContentsCache cache,
            SocketAddress clientAddress,
            SocketAddress serverAddress,
            StaticContentsManager staticContentsManager,
            Runnable onClientDisconnected,
            BackendHealthManager backendHealthManager,
            RequestsLogger requestsLogger,
            FullHttpMessageLogger fullHttpMessageLogger,
            String listenerHost,
            int listenerPort,
            boolean secure) {
        this.staticContentsManager = staticContentsManager;
        this.cache = cache;
        this.mapper = mapper;
        this.connectionsManager = connectionsManager;
        this.filters = filters;
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
        this.connectionStartsTs = System.nanoTime();
        this.onClientDisconnected = onClientDisconnected;
        this.backendHealthManager = backendHealthManager;
        this.requestsLogger = requestsLogger;
        this.fullHttpMessageLogger = fullHttpMessageLogger;
        this.listenerHost = listenerHost;
        this.listenerPort = listenerPort;
        this.secure = secure;

        this.totalRequests = TOTAL_REQUESTS_COUNTER_PER_LISTENER.labels(this.listenerHost + "_" + this.listenerPort);
    }

    public SocketAddress getClientAddress() {
        return clientAddress;
    }

    public SocketAddress getServerAddress() {
        return serverAddress;
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        onClientDisconnected.run();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        LOG.log(Level.SEVERE, "bad error", cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.ALL_IDLE) {
                CarapaceLogger.debug("Idle connection detected for client {0}. Channel closed.", clientAddress);
                ctx.close();
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.log(Level.FINEST, "{0} channelRead0 {1}", new Object[]{this, msg});
        }
        if (msg instanceof HttpRequest) {
            if (refuseOtherRequests) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "{0} refuseOtherRequests", this);
                }
                ctx.close();
                return;
            }
            HttpRequest request = (HttpRequest) msg;
            boolean _keepAlive = HttpUtil.isKeepAlive(request);
            if (!_keepAlive) {
                this.keepAlive = false;
                refuseOtherRequests = true;
            }
            if (secure) {
                detectSSLProperties(ctx);
            }
            RUNNING_REQUESTS_GAUGE.inc();
            totalRequests.inc();
            RequestHandler currentRequest = new RequestHandler(requestIdGenerator.incrementAndGet(),
                    request, filters, this, ctx, () -> RUNNING_REQUESTS_GAUGE.dec(), backendHealthManager, requestsLogger);
            pendingRequest.set(currentRequest);
            requestRunning = true;
            currentRequest.start();
        } else if (msg instanceof LastHttpContent) {
            if (requestRunning) {
                LastHttpContent trailer = (LastHttpContent) msg;
                pendingRequest.get().clientRequestFinished(trailer);
            } else {
                LOG.log(Level.INFO, "{0} swallow {1}, no more pending requests", new Object[]{this, msg});
                refuseOtherRequests = true;
                ctx.close();
            }
        } else if (msg instanceof HttpContent) {
            if (requestRunning) {
                // for example chunks from client
                HttpContent httpContent = (HttpContent) msg;
                pendingRequest.get().continueClientRequest(httpContent);
            } else {
                LOG.log(Level.INFO, "{0} swallow {1}, no more pending requests", new Object[]{this, msg});
                refuseOtherRequests = true;
                ctx.close();
            }
        }

        fullHttpMessageLogger.fireChannelRead(ctx, msg);
    }

    private void detectSSLProperties(ChannelHandlerContext ctx) {
        SslHandler handler = ctx.channel().pipeline().get(SslHandler.class);
        if (handler == null) {
            return;
        }
        if (sslProtocol == null) {
            sslProtocol = handler.engine().getSession().getProtocol();
        }
        if (cipherSuite == null) {
            cipherSuite = handler.engine().getSession().getCipherSuite();
        }
    }

    void closeIfNotKeepAlive(final ChannelHandlerContext channelToClient) {
        if (!keepAlive) {
            refuseOtherRequests = true;
            channelToClient.close();
        }
    }

    boolean isKeepAlive() {
        return keepAlive;
    }

    public String getListenerHost() {
        return listenerHost;
    }

    public int getListenerPort() {
        return listenerPort;
    }

    public boolean isSecure() {
        return secure;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public String getCipherSuite() {
        return cipherSuite;
    }

    public int getTotalRequestsCount() {
        return (int) this.totalRequests.get();
    }

    public void errorSendingRequest(RequestHandler request, EndpointConnectionImpl endpointConnection, ChannelHandlerContext peerChannel, Throwable error) {
        requestRunning = false;
        mapper.endpointFailed(endpointConnection.getKey(), error);
        LOG.log(Level.INFO, error, () -> this + " errorSendingRequest " + endpointConnection);
    }

    public void lastHttpContentSent(RequestHandler requestHandler) {
        requestRunning = false;
    }

    @Override
    public String toString() {
        return "ClientConnectionHandler{chid=" + id + ",ka=" + keepAlive + '}';
    }

    public AtomicReference<RequestHandler> getPendingRequest() {
        return pendingRequest;
    }
}
