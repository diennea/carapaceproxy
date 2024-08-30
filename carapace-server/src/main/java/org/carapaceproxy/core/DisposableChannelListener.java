package org.carapaceproxy.core;

import static reactor.netty.ConnectionObserver.State.CONNECTED;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import jdk.net.ExtendedSocketOptions;
import org.carapaceproxy.core.ssl.SslContextConfigurator;
import org.carapaceproxy.core.stats.ListenerStats;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableChannel;
import reactor.netty.FutureMono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * This class models a single listener for a {@link HttpProxyServer}.
 * <br>
 * This is meant to be a one-use class: once built, it can only be started and stopped.
 */
public class DisposableChannelListener {
    private static final Logger LOG = LoggerFactory.getLogger(DisposableChannelListener.class);

    private final HttpProxyServer parent;
    private final HostPort hostPort;
    private final NetworkListenerConfiguration configuration;
    private final ListenerStats stats;
    private final ConcurrentMap<String, SslContext> sslContextsCache;
    private ListenerStats.StatCounter totalRequests;
    private DisposableChannel listeningChannel;

    public DisposableChannelListener(final HostPort hostPort, final HttpProxyServer parent, final NetworkListenerConfiguration configuration, final ListenerStats stats, final ConcurrentMap<String, SslContext> sslContextsCache) {
        this.parent = parent;
        this.hostPort = hostPort;
        this.configuration = configuration;
        // todo I think we need to address this at some point
        // this.configuration = getCurrentConfiguration().getListener(hostPort);
        // requireNonNull(this.configuration, "Parent server configuration doesn't define any listener for " + hostPort);
        this.sslContextsCache = sslContextsCache;
        this.listeningChannel = null;
        this.stats = stats;
        this.totalRequests = null;
    }

    private RuntimeServerConfiguration getCurrentConfiguration() {
        return parent.getCurrentConfiguration();
    }

    public NetworkListenerConfiguration getConfig() {
        return configuration;
    }

    public HostPort getHostPort() {
        return hostPort;
    }

    private File getBasePath() {
        return parent.getBasePath();
    }

    public void start() {
        final var hostPort = this.hostPort.offsetPort(parent.getListenersOffsetPort());
        final var config = this.configuration;
        final var currentConfiguration = getCurrentConfiguration();
        final var clients = stats.clients();
        totalRequests = stats.requests(hostPort);
        LOG.info("Starting listener at {} ssl:{}", hostPort, config.isSsl());
        var httpServer = HttpServer.create()
                .host(hostPort.host())
                .port(hostPort.port())
                .protocol(config.getProtocols().toArray(HttpProtocol[]::new));
        if (config.isSsl()) {
            httpServer = httpServer.secure(new SslContextConfigurator(parent, config, hostPort, sslContextsCache));
        }
        httpServer = httpServer
                .metrics(true, Function.identity())
                .forwarded(ForwardedStrategy.of(config.getForwardedStrategy(), config.getTrustedIps()))
                .option(ChannelOption.SO_BACKLOG, config.getSoBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, config.isKeepAlive())
                .runOn(parent.getEventLoopGroup())
                .childOption(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPIDLE
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), config.getKeepAliveIdle())
                .childOption(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPINTVL
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), config.getKeepAliveInterval())
                .childOption(Epoll.isAvailable()
                        ? EpollChannelOption.TCP_KEEPCNT
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), config.getKeepAliveCount())
                .maxKeepAliveRequests(config.getMaxKeepAliveRequests())
                .doOnChannelInit((observer, channel, remoteAddress) -> {
                    final var handler = new IdleStateHandler(0, 0, currentConfiguration.getClientsIdleTimeoutSeconds());
                    channel.pipeline().addFirst("idleStateHandler", handler);
                    /* // todo
                    if (config.isSsl()) {
                        SniHandler sni = new SniHandler(listeningChannel) {
                            @Override
                            protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
                                SslHandler handler = super.newSslHandler(context, allocator);
                                if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported()) {
                                    Certificate cert = (Certificate) context.attributes().attr(AttributeKey.valueOf(OCSP_CERTIFICATE_CHAIN)).get();
                                    if (cert != null) {
                                        try {
                                            ReferenceCountedOpenSslEngine engine = (ReferenceCountedOpenSslEngine) handler.engine();
                                            engine.setOcspResponse(parent.getOcspStaplingManager().getOcspResponseForCertificate(cert)); // setting proper ocsp response
                                        } catch (IOException ex) {
                                            LOG.error("Error setting OCSP response.", ex);
                                        }
                                    } else {
                                        LOG.error("Cannot set OCSP response without the certificate");
                                    }
                                }
                                return handler;
                            }
                        };
                        channel.pipeline().addFirst(sni);
                    } */
                })
                .doOnConnection(conn -> {
                    clients.increment();
                    conn.channel().closeFuture().addListener(e -> clients.decrement());
                    config.getGroup().add(conn.channel());
                })
                .childObserve((connection, state) -> {
                    if (state == CONNECTED) {
                        UriCleanerHandler.INSTANCE.addToPipeline(connection.channel());
                    }
                })
                .httpRequestDecoder(option -> option.maxHeaderSize(currentConfiguration.getMaxHeaderSize()))
                .handle((request, response) -> {
                    if (CarapaceLogger.isLoggingDebugEnabled()) {
                        CarapaceLogger.debug("Receive request " + request.uri()
                                             + " From " + request.remoteAddress()
                                             + " Timestamp " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")));
                    }
                    totalRequests.increment();
                    ProxyRequest proxyRequest = new ProxyRequest(request, response, hostPort);
                    return parent.getProxyRequestsManager().processRequest(proxyRequest);
                });
        if (currentConfiguration.getResponseCompressionThreshold() >= 0) {
            CarapaceLogger.debug("Response compression enabled with min size = {0} bytes for listener {1}",
                    currentConfiguration.getResponseCompressionThreshold(), hostPort
            );
            httpServer = httpServer.compress(currentConfiguration.getResponseCompressionThreshold());
        } else {
            CarapaceLogger.debug("Response compression disabled for listener {0}", hostPort);
        }
        // Initialization of event loop groups, native transport libraries and the native libraries for the security
        httpServer.warmup().block();
        this.listeningChannel = httpServer.bindNow(); // blocking
        LOG.info("started listener at {}: {}", hostPort, this.listeningChannel);
    }

    /**
     * Stop the listener and free the port.
     *
     * @see DisposableChannel#disposeNow(Duration)
     */
    public void stop() throws InterruptedException {
        if (this.isStarted()) {
            this.listeningChannel.disposeNow(Duration.ofSeconds(10));
            FutureMono.from(this.configuration.getGroup().close()).block(Duration.ofSeconds(10));
        }
    }

    /**
     * Check whether the listener is actually started.
     *
     * @return true is the server was started, or false if it was never started, or if it was stopped
     */
    public boolean isStarted() {
        return this.listeningChannel != null && !this.listeningChannel.isDisposed();
    }

    /**
     * The actual HTTP port used by the channel might seldom differ from {@link #getHostPort() the one declared}.
     * <br>
     * A common example is unit tests, but there might also be real-world cases.
     *
     * @return the actual port from the {@link DisposableChannel}
     */
    public int getActualPort() {
        if (this.listeningChannel != null) {
            if (this.listeningChannel.address() instanceof InetSocketAddress address) {
                return address.getPort();
            }
            LOG.warn("Unexpected listening channel address type {}", this.listeningChannel.address().getClass());
        }
        return hostPort.port();
    }

    public int getTotalRequests() {
        return totalRequests.get();
    }

    public void clear() {
        this.sslContextsCache.clear();
    }
}
