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
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.prometheus.client.Gauge;
import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.net.ExtendedSocketOptions;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * Collection of listeners waiting for incoming clients requests on the configured HTTP ports.
 * <br>
 * While the {@link RuntimeServerConfiguration} is actually <i>mutable</i>, this class won't watch it for updates;
 * the caller should request a {@link #reloadCurrentConfiguration() reload of the configuration} manually instead.
 *
 * @author enrico.olivelli
 */
public class Listeners {

    public static final String OCSP_CERTIFICATE_CHAIN = "ocsp-certificate";
    private static final Logger LOG = Logger.getLogger(Listeners.class.getName());
    private static final Gauge CURRENT_CONNECTED_CLIENTS_GAUGE = PrometheusUtils.createGauge(
            "clients", "current_connected", "currently connected clients"
    ).register();

    private final HttpProxyServer parent;
    private final Map<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final Map<HostPort, ListeningChannel> listeningChannels = new ConcurrentHashMap<>();
    private final File basePath;
    private boolean started;

    private RuntimeServerConfiguration currentConfiguration;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.currentConfiguration = parent.getCurrentConfiguration();
        this.basePath = parent.getBasePath();
    }

    public int getLocalPort() {
        for (ListeningChannel c : listeningChannels.values()) {
            InetSocketAddress addr = (InetSocketAddress) c.getChannel().address();
            return addr.getPort();
        }
        return -1;
    }

    public Map<HostPort, ListeningChannel> getListeningChannels() {
        return listeningChannels;
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadConfiguration(currentConfiguration);
    }

    public void stop() {
        for (HostPort key : listeningChannels.keySet()) {
            try {
                stopListener(key);
            } catch (InterruptedException ex) {
                LOG.log(Level.SEVERE, "Interrupted while stopping a listener", ex);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopListener(HostPort hostport) throws InterruptedException {
        ListeningChannel channel = listeningChannels.remove(hostport);
        if (channel != null) {
            channel.channel.disposeNow(Duration.ofSeconds(10));
            FutureMono.from(channel.getConfig().getGroup().close()).block(Duration.ofSeconds(10));
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

    /**
     * Apply a new configuration and refresh the listeners according to it.
     *
     * @param newConfiguration the configuration
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     * @see #reloadCurrentConfiguration()
     */
    void reloadConfiguration(RuntimeServerConfiguration newConfiguration) throws InterruptedException {
        if (!started) {
            this.currentConfiguration = newConfiguration;
            return;
        }
        // Clear cached ssl contexts
        sslContexts.clear();

        // stop dropped listeners, start new one
        List<HostPort> listenersToStop = new ArrayList<>();
        List<HostPort> listenersToRestart = new ArrayList<>();
        for (Map.Entry<HostPort, ListeningChannel> channel : listeningChannels.entrySet()) {
            HostPort key = channel.getKey();
            NetworkListenerConfiguration actualListenerConfig = currentConfiguration.getListener(key);
            NetworkListenerConfiguration newConfigurationForListener = newConfiguration.getListener(key);
            if (newConfigurationForListener == null) {
                LOG.log(Level.INFO, "listener: {0} is to be shut down", key);
                listenersToStop.add(key);
            } else if (!newConfigurationForListener.equals(actualListenerConfig)
                    || newConfiguration.getResponseCompressionThreshold() != currentConfiguration.getResponseCompressionThreshold()
                    || newConfiguration.getMaxHeaderSize() != currentConfiguration.getMaxHeaderSize()) {
                LOG.log(Level.INFO, "listener: {0} is to be restarted", key);
                listenersToRestart.add(key);
            }
            channel.getValue().clear();
        }
        List<HostPort> listenersToStart = new ArrayList<>();
        for (NetworkListenerConfiguration config : newConfiguration.getListeners()) {
            HostPort key = config.getKey();
            if (!listeningChannels.containsKey(key)) {
                LOG.log(Level.INFO, "listener: {0} is to be started", key);
                listenersToStart.add(key);
            }
        }

        // apply new configuration, this has to be done before rebooting listeners
        currentConfiguration = newConfiguration;

        try {
            for (HostPort hostport : listenersToStop) {
                LOG.log(Level.INFO, "Stopping {0}", hostport);
                stopListener(hostport);
            }

            for (HostPort hostport : listenersToRestart) {
                LOG.log(Level.INFO, "Restart {0}", hostport);
                stopListener(hostport);
                NetworkListenerConfiguration newConfigurationForListener = currentConfiguration.getListener(hostport);
                bootListener(newConfigurationForListener);
            }

            for (HostPort hostport : listenersToStart) {
                LOG.log(Level.INFO, "Starting {0}", hostport);
                NetworkListenerConfiguration newConfigurationForListener = currentConfiguration.getListener(hostport);
                bootListener(newConfigurationForListener);
            }
        } catch (InterruptedException stopMe) {
            Thread.currentThread().interrupt();
            throw stopMe;
        }
    }

    private void bootListener(NetworkListenerConfiguration config) throws InterruptedException {
        HostPort hostPort = new HostPort(config.getHost(), config.getPort() + parent.getListenersOffsetPort());
        ListeningChannel listeningChannel = new ListeningChannel(basePath, currentConfiguration, parent, sslContexts, hostPort, config);
        LOG.log(Level.INFO, "Starting listener at {0}:{1} ssl:{2}", new Object[]{hostPort.host(), String.valueOf(hostPort.port()), config.isSsl()});

        // Listener setup
        HttpServer httpServer = HttpServer.create()
                .host(hostPort.host())
                .port(hostPort.port())
                .protocol(config.getProtocols().toArray(HttpProtocol[]::new))
                .secure(new ListenerSslProviderBuilder(basePath, currentConfiguration, parent, hostPort, config))
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
                    channel.pipeline().addFirst("idleStateHandler", new IdleStateHandler(0, 0, currentConfiguration.getClientsIdleTimeoutSeconds()));
                    /* if (config.isSsl()) {
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
                                            LOG.log(Level.SEVERE, "Error setting OCSP response.", ex);
                                        }
                                    } else {
                                        LOG.log(Level.SEVERE, "Cannot set OCSP response without the certificate");
                                    }
                                }
                                return handler;
                            }
                        };
                        channel.pipeline().addFirst(sni);
                    } */
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
                    if (CarapaceLogger.isLoggingDebugEnabled()) {
                        CarapaceLogger.debug("Receive request " + request.uri()
                        + " From " + request.remoteAddress()
                        + " Timestamp " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")));
                    }

                    ListeningChannel channel = listeningChannels.get(hostPort);
                    if (channel != null) {
                        channel.incRequests();
                    }
                    ProxyRequest proxyRequest = new ProxyRequest(request, response, hostPort);
                    return parent.getProxyRequestsManager().processRequest(proxyRequest);
                });

        // response compression
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

        // Listener startup
        DisposableServer channel = httpServer.bindNow(); // blocking
        listeningChannel.setChannel(channel);
        listeningChannels.put(hostPort, listeningChannel);
        LOG.log(Level.INFO, "started listener at {0}: {1}", new Object[]{hostPort, channel});
    }

    public SSLCertificateConfiguration chooseCertificate(String sniHostname, String defaultCertificate) {
        return chooseCertificate(currentConfiguration, sniHostname, defaultCertificate);
    }

    public static SSLCertificateConfiguration chooseCertificate(final RuntimeServerConfiguration currentConfiguration, String sniHostname, String defaultCertificate) {
        if (sniHostname == null) {
            sniHostname = "";
        }
        Map<String, SSLCertificateConfiguration> certificates = currentConfiguration.getCertificates();
        SSLCertificateConfiguration certificateMatchExact = null;
        SSLCertificateConfiguration certificateMatchNoExact = null;
        for (SSLCertificateConfiguration c : certificates.values()) {
            if (certificateMatches(sniHostname, c, true)) {
                certificateMatchExact = c;
            } else if (certificateMatches(sniHostname, c, false)) {
                if (certificateMatchNoExact == null || c.isMoreSpecific(certificateMatchNoExact)) {
                    certificateMatchNoExact = c;
                }
            }
        }
        SSLCertificateConfiguration choosen = null;
        if (certificateMatchExact != null) {
            choosen = certificateMatchExact;
        } else if (certificateMatchNoExact != null) {
            choosen = certificateMatchNoExact;
        }
        if (choosen == null) {
            choosen = certificates.get(defaultCertificate);
        }
        return choosen;
    }

    private static boolean certificateMatches(String hostname, SSLCertificateConfiguration c, boolean exact) {
        if (c.getSubjectAltNames() == null || c.getSubjectAltNames().isEmpty()) {
            if (exact) {
                return !c.isWildcard() && hostname.equals(c.getHostname());
            }
            return c.isWildcard() && hostname.endsWith(c.getHostname());
        }
        for (var name: c.getNames()) {
            final var wildcard = CertificatesUtils.isWildcard(name);
            if (exact && !wildcard && hostname.equals(name)) {
                return true;
            }
            if (!exact && wildcard && hostname.endsWith(CertificatesUtils.removeWildcard(name))) {
                return true;
            }
        }
        return false;
    }
}
