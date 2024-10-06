package org.carapaceproxy.core;

import static reactor.netty.ConnectionObserver.State.CONNECTED;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import jdk.net.ExtendedSocketOptions;
import lombok.SneakyThrows;
import org.carapaceproxy.core.stats.ListenerStats;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableChannel;
import reactor.netty.FutureMono;
import reactor.netty.http.Http11SslContextSpec;
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
    private final RuntimeServerConfiguration runtimeConfiguration;
    private final NetworkListenerConfiguration listenerConfiguration;
    private final ListenerStats stats;
    private final ConcurrentMap<String, SslContext> sslContextsCache;
    private ListenerStats.StatCounter totalRequests;
    private DisposableChannel listeningChannel;

    public DisposableChannelListener(final HostPort hostPort, final HttpProxyServer parent, final RuntimeServerConfiguration runtimeConfiguration, final NetworkListenerConfiguration listenerConfiguration, final ListenerStats stats, final ConcurrentMap<String, SslContext> sslContextsCache) {
        this.parent = parent;
        this.hostPort = hostPort;
        this.runtimeConfiguration = runtimeConfiguration;
        this.listenerConfiguration = listenerConfiguration;
        this.sslContextsCache = sslContextsCache;
        this.listeningChannel = null;
        this.stats = stats;
        this.totalRequests = null;
    }

    public NetworkListenerConfiguration getConfig() {
        return listenerConfiguration;
    }

    public HostPort getHostPort() {
        return hostPort;
    }

    @SneakyThrows
    public void start() {
        final var hostPort = this.hostPort.offsetPort(parent.getListenersOffsetPort());
        final var config = this.listenerConfiguration;
        final var clients = stats.clients();
        totalRequests = stats.requests(hostPort);
        LOG.info("Starting listener at {} ssl:{}", hostPort, config.isSsl());
        var httpServer = HttpServer.create()
                .host(hostPort.host())
                .port(hostPort.port())
                .protocol(config.getProtocols().toArray(HttpProtocol[]::new));
        if (config.isSsl()) {
            // final var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            // final var sslContext = SslContextBuilder
            //         .forServer(keyManager)
            //         .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
            //         .protocols(config.getSslProtocols())
            //         .enableOcsp(true)
            //         /* .applicationProtocolConfig(new ApplicationProtocolConfig(
            //                 ApplicationProtocolConfig.Protocol.ALPN,
            //                 // NO_ADVERTISE means do not send the protocol name if it's unsupported
            //                 ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            //                 // ACCEPT means select the first protocol if no match is found
            //                 ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            //                 ApplicationProtocolNames.HTTP_2,
            //                 ApplicationProtocolNames.HTTP_1_1
            //         )) */
            //         .build();
            // httpServer = httpServer.secure(spec -> spec.sslContext(sslContext));
            final var cert = new SelfSignedCertificate();
            final var http11SslContextSpec = Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
            httpServer = httpServer.secure(sslContextSpec -> sslContextSpec.sslContext(http11SslContextSpec));
        }
        final var epollAvailable = Epoll.isAvailable();
        LOG.info("Epoll is available? {}", epollAvailable);
        httpServer = httpServer
                .metrics(true, Function.identity())
                .forwarded(ForwardedStrategy.of(config.getForwardedStrategy(), config.getTrustedIps()))
                .option(ChannelOption.SO_BACKLOG, config.getSoBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, config.isKeepAlive())
                .runOn(parent.getEventLoopGroup())
                .childOption(epollAvailable
                        ? EpollChannelOption.TCP_KEEPIDLE
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), config.getKeepAliveIdle())
                .childOption(epollAvailable
                        ? EpollChannelOption.TCP_KEEPINTVL
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL), config.getKeepAliveInterval())
                .childOption(epollAvailable
                        ? EpollChannelOption.TCP_KEEPCNT
                        : NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT), config.getKeepAliveCount())
                .maxKeepAliveRequests(config.getMaxKeepAliveRequests())
                .doOnChannelInit((observer, channel, remoteAddress) -> {
                    final var handler = new IdleStateHandler(0, 0, this.runtimeConfiguration.getClientsIdleTimeoutSeconds());
                    channel.pipeline().addFirst("idleStateHandler", handler);
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
                .httpRequestDecoder(option -> option.maxHeaderSize(this.runtimeConfiguration.getMaxHeaderSize()))
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
        if (this.runtimeConfiguration.getResponseCompressionThreshold() >= 0) {
            CarapaceLogger.debug("Response compression enabled with min size = {0} bytes for listener {1}",
                    this.runtimeConfiguration.getResponseCompressionThreshold(), hostPort
            );
            httpServer = httpServer.compress(this.runtimeConfiguration.getResponseCompressionThreshold());
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
            FutureMono.from(this.listenerConfiguration.getGroup().close()).block(Duration.ofSeconds(10));
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
