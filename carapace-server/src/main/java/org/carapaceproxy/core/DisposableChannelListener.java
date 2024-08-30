package org.carapaceproxy.core;

import static java.util.Objects.requireNonNull;
import static reactor.netty.ConnectionObserver.State.CONNECTED;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.File;
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
 * A sensible usage is:
 * <ol>
 *     <li>build it: it will snapshot a {@link NetworkListenerConfiguration}</li>
 *     <li>{@link #start()} it</li>
 *     <li>let it handle its own replacement through {@link #reloadOrNull()}</li>
 *     <li>store the reference to the <b>new</b> instance of this class, if any</li>
 * </ol>
 */
public class DisposableChannelListener {
    private static final Logger LOG = LoggerFactory.getLogger(DisposableChannelListener.class);

    private final HttpProxyServer parent;
    private final HostPort hostPort;
    private final NetworkListenerConfiguration configuration;
    private final ListenerStats stats;
    private final ConcurrentMap<String, SslContext> sslContextsCache;
    private DisposableChannel listeningChannel;

    public DisposableChannelListener(final HostPort hostPort, final HttpProxyServer parent, final ListenerStats stats, final ConcurrentMap<String, SslContext> sslContextsCache) {
        this.parent = parent;
        this.hostPort = hostPort;
        this.configuration = getCurrentConfiguration().getListener(hostPort);
        requireNonNull(this.configuration, "Parent server configuration doesn't define any listener for " + hostPort);
        this.sslContextsCache = sslContextsCache;
        this.listeningChannel = null;
        this.stats = stats;
    }

    private RuntimeServerConfiguration getCurrentConfiguration() {
        return parent.getCurrentConfiguration();
    }

    public HostPort getHostPort() {
        return hostPort;
    }

    private File getBasePath() {
        return parent.getBasePath();
    }

    public void start() {
        final var hostPort = getRealHostPort();
        final var config = this.configuration;
        final var currentConfiguration = getCurrentConfiguration();
        final var requestCounter = stats.requests(hostPort);
        final var clientsCounter = stats.clients();
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
                .maxKeepAliveRequests(config.getMaxKeepAliveRequests()).doOnChannelInit((observer, channel, remoteAddress) -> channel.pipeline().addFirst(
                        "idleStateHandler",
                        new IdleStateHandler(0, 0, currentConfiguration.getClientsIdleTimeoutSeconds())
                ))
                .doOnConnection(conn -> {
                    clientsCounter.increment();
                    conn.channel().closeFuture().addListener(e -> clientsCounter.decrement());
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
                    requestCounter.increment();
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

    public HostPort getRealHostPort() {
        return hostPort.offsetPort(parent.getListenersOffsetPort());
    }

    /**
     * This class is meant to be immutable like {@link NetworkListenerConfiguration},
     * while {@link HttpProxyServer} and {@link RuntimeServerConfiguration} are not.
     * <br>
     * This tests parent configuration for modifications:
     * <ul>
     *     <li>if the network configuration is still the same, it returns himself</li>
     *     <li>if the network configuration changed, this {@link #stop() is stopped} and a new listener is returned</li>
     *     <li>if the network configuration was removed, this {@link #stop() is stopped} and null is returned</li>
     * </ul>
     *
     * @return a valid listener for the current {@link #getHostPort() host and port}, or null if it was removed
     */
    public DisposableChannelListener reloadOrNull() {
        final var newConfiguration = getCurrentConfiguration().getListener(hostPort);
        if (newConfiguration == this.configuration) {
            LOG.info("listener: {} if fine", hostPort);
            return this;
        }
        this.stop();
        if (newConfiguration == null) {
            LOG.info("listener: {} is to be shut down", hostPort);
            return null;
        }
        LOG.info("listener: {} is to be restarted", hostPort);
        return new DisposableChannelListener(hostPort, parent, stats, sslContextsCache);
    }

    /**
     * Stop the listener and free the port.
     * <br>
     * This is usually self-invoked through {@link #reloadOrNull()}.
     *
     * @see DisposableChannel#disposeNow(Duration)
     */
    public void stop() {
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
}
