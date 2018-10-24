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

import nettyhttpproxy.client.ConnectionsManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.client.impl.EndpointConnectionImpl;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.cache.ContentsCache;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.StatsLogger;

public class ClientConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger LOG = Logger.getLogger(ClientConnectionHandler.class.getName());
    private static final AtomicLong idgenerator = new AtomicLong();
    private final long id = idgenerator.incrementAndGet();

    private final static AtomicLong requestIdGenerator = new AtomicLong();

    final EndpointMapper mapper;
    final StatsLogger mainLogger;
    final Counter totalRequests;
    final Counter runningRequests;
    final BackendHealthManager backendHealthManager;
    final ConnectionsManager connectionsManager;
    final List<RequestFilter> filters;
    final SocketAddress clientAddress;
    final ContentsCache cache;
    final StaticContentsManager staticContentsManager;
    final RequestsLogger requestsLogger;
    final long connectionStartsTs;
    volatile Boolean keepAlive;
    volatile boolean refuseOtherRequests;
    private final List<RequestHandler> pendingRequests = new CopyOnWriteArrayList<>();
    final Runnable onClientDisconnected;

    public ClientConnectionHandler(
            StatsLogger mainLogger,
            EndpointMapper mapper,
            ConnectionsManager connectionsManager,
            List<RequestFilter> filters,
            ContentsCache cache,
            SocketAddress clientAddress,
            StaticContentsManager staticContentsManager,
            Runnable onClientDisconnected,
            BackendHealthManager backendHealthManager,
            RequestsLogger requestsLogger) {
        this.mainLogger = mainLogger;
        this.totalRequests = mainLogger.getCounter("totalrequests");
        this.runningRequests = mainLogger.getCounter("runningrequests");
        this.staticContentsManager = staticContentsManager;
        this.cache = cache;
        this.mapper = mapper;
        this.connectionsManager = connectionsManager;
        this.filters = filters;
        this.clientAddress = clientAddress;
        this.connectionStartsTs = System.nanoTime();
        this.onClientDisconnected = onClientDisconnected;
        this.backendHealthManager = backendHealthManager;
        this.requestsLogger = requestsLogger;
    }

    public SocketAddress getClientAddress() {
        return clientAddress;
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
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (refuseOtherRequests) {
            LOG.log(Level.INFO, "{0} refuseOtherRequests", this);
            ctx.close();
            return;
        }
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            RequestHandler currentRequest = new RequestHandler(requestIdGenerator.incrementAndGet(),
                    request, filters, mainLogger, this, ctx, () -> runningRequests.dec(), backendHealthManager, requestsLogger);
            addPendingRequest(currentRequest);
            currentRequest.start();
        } else if (msg instanceof LastHttpContent) {
            LastHttpContent trailer = (LastHttpContent) msg;
            try {
                RequestHandler currentRequest = pendingRequests.get(0);
                currentRequest.clientRequestFinished(trailer);
                totalRequests.inc();
                runningRequests.inc();
            } catch (java.lang.ArrayIndexOutOfBoundsException noMorePendingRequests) {
                LOG.info(this + " swallow " + msg + ", no more pending requests");
                refuseOtherRequests = true;
                ctx.close();
            }
        } else if (msg instanceof HttpContent) {
            // for example chunks from client
            HttpContent httpContent = (HttpContent) msg;
            try {
                RequestHandler currentRequest = pendingRequests.get(0);
                currentRequest.continueClientRequest(httpContent);
            } catch (java.lang.ArrayIndexOutOfBoundsException noMorePendingRequests) {
                LOG.info(this + " swallow " + msg + ", no more pending requests");
                refuseOtherRequests = true;
                ctx.close();
            }
        }
    }

    boolean isKeepAlive() {
        if (keepAlive == null) {
            return true;
        }
        return keepAlive;
    }

    public void errorSendingRequest(RequestHandler request, EndpointConnectionImpl aThis, ChannelHandlerContext peerChannel, Throwable error) {
        pendingRequests.remove(request);
        mapper.endpointFailed(aThis.getKey(), error);
        LOG.log(Level.INFO, error, () -> this + " errorSendingRequest " + aThis);
    }

    public void lastHttpContentSent(RequestHandler requestHandler) {
        if (!requestHandler.isKeepAlive()) {
            keepAlive = false;
        } else if (keepAlive == null) {
            keepAlive = true;
        }
        pendingRequests.remove(requestHandler);
    }

    @Override
    public String toString() {
        return "ClientConnectionHandler{" + id + ",ka=" + keepAlive + '}';
    }

    void addPendingRequest(RequestHandler request) {
        pendingRequests.add(request);
    }

}
