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
package org.carapaceproxy.core;

import static reactor.netty.ConnectionObserver.State.CONNECTED;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.prometheus.client.Gauge;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import jdk.net.ExtendedSocketOptions;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * Collection of listeners waiting for incoming clients requests on the configured HTTP ports.
 * <br>
 * While the {@link RuntimeServerConfiguration} is actually <i>mutable</i>, this class won't watch it for updates;
 * the caller should instead
 * request a {@link #reloadConfiguration(RuntimeServerConfiguration) reload of the configuration} manually.
 *
 * @author enrico.olivelli
 */
public class Listeners {

    public static final String OCSP_CERTIFICATE_CHAIN = "ocsp-certificate";

    private static final Logger LOG = LoggerFactory.getLogger(Listeners.class);

    private static final Gauge CURRENT_CONNECTED_CLIENTS_GAUGE = PrometheusUtils.createGauge(
            "clients", "current_connected", "currently connected clients"
    ).register();

    private final HttpProxyServer parent;
    private final ConcurrentMap<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<EndpointKey, ListeningChannel> listeningChannels = new ConcurrentHashMap<>();
    private final File basePath;
    private boolean started;
    private RuntimeServerConfiguration currentConfiguration;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.currentConfiguration = parent.getCurrentConfiguration();
        this.basePath = parent.getBasePath();
    }

    public RuntimeServerConfiguration getCurrentConfiguration() {
        return currentConfiguration;
    }

    public int getLocalPort() {
        for (final ListeningChannel listeningChannel : listeningChannels.values()) {
            return listeningChannel.getLocalPort();
        }
        return -1;
    }

    public Map<EndpointKey, ListeningChannel> getListeningChannels() {
        return listeningChannels;
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadConfiguration(currentConfiguration);
    }

    /**
     * Apply a new configuration and refresh the listeners according to it.
     *
     * @param newConfiguration the configuration
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     * @see #reloadCurrentConfiguration()
     */
    void reloadConfiguration(final RuntimeServerConfiguration newConfiguration) throws InterruptedException {
        if (!started) {
            this.currentConfiguration = newConfiguration;
            return;
        }
        // Clear cached ssl contexts
        sslContexts.clear();

        // stop dropped listeners, start new one
        final List<EndpointKey> listenersToStop = new ArrayList<>();
        final List<EndpointKey> listenersToRestart = new ArrayList<>();
        for (final Map.Entry<EndpointKey, ListeningChannel> channel : listeningChannels.entrySet()) {
            final EndpointKey hostPort = channel.getKey();
            final NetworkListenerConfiguration actualListenerConfig = currentConfiguration.getListener(hostPort);
            final NetworkListenerConfiguration newConfigurationForListener = newConfiguration.getListener(hostPort);
            if (newConfigurationForListener == null) {
                LOG.info("listener: {} is to be shut down", hostPort);
                listenersToStop.add(hostPort);
            } else if (!newConfigurationForListener.equals(actualListenerConfig)
                       || newConfiguration.getResponseCompressionThreshold() != currentConfiguration.getResponseCompressionThreshold()
                       || newConfiguration.getMaxHeaderSize() != currentConfiguration.getMaxHeaderSize()) {
                LOG.info("listener: {} is to be restarted", hostPort);
                listenersToRestart.add(hostPort);
            }
            channel.getValue().clear();
        }
        final List<EndpointKey> listenersToStart = new ArrayList<>();
        for (final NetworkListenerConfiguration config : newConfiguration.getListeners()) {
            final EndpointKey key = config.getKey();
            if (!listeningChannels.containsKey(key)) {
                LOG.info("listener: {} is to be started", key);
                listenersToStart.add(key);
            }
        }

        // apply new configuration, this has to be done before rebooting listeners
        currentConfiguration = newConfiguration;

        try {
            for (final EndpointKey hostPort : listenersToStop) {
                LOG.info("Stopping {}", hostPort);
                stopListener(hostPort);
            }

            for (final EndpointKey hostPort : listenersToRestart) {
                LOG.info("Restart {}", hostPort);
                stopListener(hostPort);
                final var newConfigurationForListener = currentConfiguration.getListener(hostPort);
                bootListener(newConfigurationForListener);
            }

            for (final EndpointKey hostPort : listenersToStart) {
                LOG.info("Starting {}", hostPort);
                final var newConfigurationForListener = currentConfiguration.getListener(hostPort);
                bootListener(newConfigurationForListener);
            }
        } catch (final InterruptedException stopMe) {
            Thread.currentThread().interrupt();
            throw stopMe;
        }
    }

    private void stopListener(EndpointKey hostPort) throws InterruptedException {
        final var channel = listeningChannels.remove(hostPort);
        if (channel != null) {
            channel.disposeChannel();
        }
    }

    private void bootListener(final NetworkListenerConfiguration config) throws InterruptedException {
        final EndpointKey hostPort = new EndpointKey(config.getHost(), config.getPort()).offsetPort(parent.getListenersOffsetPort());
        final ListeningChannel listeningChannel = new ListeningChannel(basePath, currentConfiguration, parent, sslContexts, hostPort, config);
        LOG.info("Starting listener at {}:{} ssl:{}", hostPort.host(), hostPort.port(), config.isSsl());

        // Listener setup
        HttpServer httpServer = HttpServer.create()
                .host(hostPort.host())
                .port(hostPort.port())
                .protocol(config.getProtocols().toArray(HttpProtocol[]::new))
                /*
                  // .secure()
                  todo: to enable H2, see config.isSsl() & snimappings
                  see https://projectreactor.io/docs/netty/release/reference/index.html#_server_name_indication_3
                 */
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
                    final ChannelHandler idle = new IdleStateHandler(0, 0, currentConfiguration.getClientsIdleTimeoutSeconds());
                    channel.pipeline().addFirst("idleStateHandler", idle);
                    if (config.isSsl()) {
                        final ChannelHandler sni = new ListenersSniHandler(currentConfiguration, parent, listeningChannel);
                        channel.pipeline().addFirst(sni);
                    }
                })
                .doOnConnection(conn -> {
                    CURRENT_CONNECTED_CLIENTS_GAUGE.inc();
                    conn.channel().closeFuture().addListener(e -> CURRENT_CONNECTED_CLIENTS_GAUGE.dec());
                    config.getGroup().add(conn.channel());
                })
                .childObserve((connection, state) -> {
                    if (state == CONNECTED) {
                        UriCleanerHandler.INSTANCE.addToPipeline(connection.channel());
                    }
                })
                .httpRequestDecoder(option -> option.maxHeaderSize(currentConfiguration.getMaxHeaderSize()))
                .handle((request, response) -> { // Custom request-response handling
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "Receive request {} From {} Timestamp {}",
                                request.uri(),
                                request.remoteAddress(),
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS"))
                        );
                    }

                    final ListeningChannel channel = listeningChannels.get(hostPort);
                    if (channel != null) {
                        channel.incRequests();
                    }
                    final ProxyRequest proxyRequest = new ProxyRequest(request, response, hostPort);
                    return parent.getProxyRequestsManager().processRequest(proxyRequest);
                });

        // response compression
        if (currentConfiguration.getResponseCompressionThreshold() >= 0) {
            LOG.debug(
                    "Response compression enabled with min size = {} bytes for listener {}", currentConfiguration.getResponseCompressionThreshold(), hostPort);
            httpServer = httpServer.compress(currentConfiguration.getResponseCompressionThreshold());
        } else {
            LOG.debug("Response compression disabled for listener {}", hostPort);
        }

        // Initialization of event loop groups, native transport libraries and the native libraries for the security
        httpServer.warmup().block();

        // Listener startup
        final DisposableServer channel = httpServer.bindNow(); // blocking
        listeningChannel.setChannel(channel);
        listeningChannels.put(hostPort, listeningChannel);
        LOG.info("started listener at {}: {}", hostPort, channel);
    }

    public void stop() {
        for (var key : listeningChannels.keySet()) {
            try {
                stopListener(key);
            } catch (InterruptedException ex) {
                LOG.error("Interrupted while stopping a listener", ex);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Re-apply the current configuration; it should be invoked after editing it.
     *
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     */
    public void reloadCurrentConfiguration() throws InterruptedException {
        reloadConfiguration(this.currentConfiguration);
    }

}
