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

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import static reactor.netty.ConnectionObserver.State.CONNECTED;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.ReferenceCountedOpenSslEngine;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import jdk.net.ExtendedSocketOptions;
import lombok.Data;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
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

    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private final HttpProxyServer parent;
    private final ConcurrentMap<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<HostPort, /*DisposableChannelListener*/ ListeningChannel> listeningChannels = new ConcurrentHashMap<>();
    private final File basePath;
    private boolean started;

    private RuntimeServerConfiguration currentConfiguration;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.currentConfiguration = parent.getCurrentConfiguration();
        this.basePath = parent.getBasePath();
    }

    public int getLocalPort() {
        for (var listeningChannel : listeningChannels.values()) {
            // org.carapaceproxy.core.DisposableChannelListener#getActualPort
            var addr = (InetSocketAddress) listeningChannel.getChannel().address();
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
        for (var key : listeningChannels.keySet()) {
            try {
                stopListener(key);
            } catch (InterruptedException ex) {
                LOG.error("Interrupted while stopping a listener", ex);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopListener(HostPort hostPort) throws InterruptedException {
        var channel = listeningChannels.remove(hostPort);
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
    void reloadConfiguration(final RuntimeServerConfiguration newConfiguration) throws InterruptedException {
        if (!started) {
            this.currentConfiguration = newConfiguration;
            return;
        }
        // Clear cached ssl contexts
        sslContexts.clear();

        // stop dropped listeners, start new one
        final var listenersToStop = new ArrayList<HostPort>();
        final var listenersToRestart = new ArrayList<HostPort>();
        for (final var channel : listeningChannels.entrySet()) {
            final var hostPort = channel.getKey();
            final var actualListenerConfig = currentConfiguration.getListener(hostPort);
            final var newConfigurationForListener = newConfiguration.getListener(hostPort);
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
        final var listenersToStart = new ArrayList<HostPort>();
        for (final var config : newConfiguration.getListeners()) {
            final var key = config.getKey();
            if (!listeningChannels.containsKey(key)) {
                LOG.info("listener: {} is to be started", key);
                listenersToStart.add(key);
            }
        }

        // apply new configuration, this has to be done before rebooting listeners
        currentConfiguration = newConfiguration;

        try {
            for (final var hostPort : listenersToStop) {
                LOG.info("Stopping {}", hostPort);
                stopListener(hostPort);
            }

            for (final var hostPort : listenersToRestart) {
                LOG.info("Restart {}", hostPort);
                stopListener(hostPort);
                final var newConfigurationForListener = currentConfiguration.getListener(hostPort);
                bootListener(newConfigurationForListener);
            }

            for (final var hostPort : listenersToStart) {
                LOG.info("Starting {}", hostPort);
                final var newConfigurationForListener = currentConfiguration.getListener(hostPort);
                bootListener(newConfigurationForListener);
            }
        } catch (final InterruptedException stopMe) {
            Thread.currentThread().interrupt();
            throw stopMe;
        }
    }

    private void bootListener(final NetworkListenerConfiguration config) throws InterruptedException {
        final var hostPort = new HostPort(config.getHost(), config.getPort()).offsetPort(parent.getListenersOffsetPort());
        final var listeningChannel = new ListeningChannel(hostPort, config);
        LOG.info("Starting listener at {}:{} ssl:{}", hostPort.host(), hostPort.port(), config.isSsl());

        // Listener setup
        var httpServer = HttpServer.create()
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
                    channel.pipeline().addFirst("idleStateHandler", new IdleStateHandler(0, 0, currentConfiguration.getClientsIdleTimeoutSeconds()));
                    if (config.isSsl()) {
                        var sni = new SniHandler(listeningChannel) {
                            @Override
                            protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
                                var handler = super.newSslHandler(context, allocator);
                                if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported()) {
                                    var cert = (Certificate) context.attributes().attr(AttributeKey.valueOf(OCSP_CERTIFICATE_CHAIN)).get();
                                    if (cert != null) {
                                        try {
                                            var engine = (ReferenceCountedOpenSslEngine) handler.engine();
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
                    if (CarapaceLogger.isLoggingDebugEnabled()) {
                        CarapaceLogger.debug("Receive request " + request.uri()
                        + " From " + request.remoteAddress()
                        + " Timestamp " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss.SSS")));
                    }

                    var channel = listeningChannels.get(hostPort);
                    if (channel != null) {
                        channel.incRequests();
                    }
                    var proxyRequest = new ProxyRequest(request, response, hostPort);
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
        var channel = httpServer.bindNow(); // blocking
        listeningChannel.setChannel(channel);
        listeningChannels.put(hostPort, listeningChannel);
        LOG.info("started listener at {}: {}", hostPort, channel);
    }

    @Data
    public final class ListeningChannel implements io.netty.util.AsyncMapping<String, SslContext> {

        private final HostPort hostPort;
        private final NetworkListenerConfiguration config;
        private final Counter.Child totalRequests;
        private final Map<String, SslContext> listenerSslContexts = new HashMap<>();
        DisposableServer channel;

        public ListeningChannel(HostPort hostPort, NetworkListenerConfiguration config) {
            this.hostPort = hostPort;
            this.config = config;
            totalRequests = TOTAL_REQUESTS_PER_LISTENER_COUNTER.labels(hostPort.host() + "_" + hostPort.port());
        }

        public void incRequests() {
            totalRequests.inc();
        }

        public void clear() {
            this.listenerSslContexts.clear();
        }

        @Override
        public Future<SslContext> map(String sniHostname, Promise<SslContext> promise) {
            try {
                var key = config.getHost() + ":" + hostPort.port() + "+" + sniHostname;
                if (LOG.isDebugEnabled()) {
                    LOG.debug("resolve SNI mapping {}, key: {}", sniHostname, key);
                }
                try {
                    var sslContext = listenerSslContexts.get(key);
                    if (sslContext != null) {
                        return promise.setSuccess(sslContext);
                    }

                    sslContext = sslContexts.computeIfAbsent(key, (k) -> {
                        try {
                            var chosen = chooseCertificate(sniHostname, config.getDefaultCertificate());
                            if (chosen == null) {
                                throw new ConfigurationNotValidException("cannot find a certificate for snihostname " + sniHostname
                                        + ", with default cert for listener as '" + config.getDefaultCertificate()
                                        + "', available " + currentConfiguration.getCertificates().keySet());
                            }
                            return bootSslContext(config, chosen);
                        } catch (ConfigurationNotValidException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                    listenerSslContexts.put(key, sslContext);

                    return promise.setSuccess(sslContext);
                } catch (RuntimeException err) {
                    if (err.getCause() instanceof ConfigurationNotValidException) {
                        throw (ConfigurationNotValidException) err.getCause();
                    } else {
                        throw new ConfigurationNotValidException(err);
                    }
                }
            } catch (ConfigurationNotValidException err) {
                LOG.error("Error booting certificate for SNI hostname {}, on listener {}", sniHostname, config);
                return promise.setFailure(err);
            }
        }

        private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
            var port = listener.getPort() + parent.getListenersOffsetPort();
            var sslCiphers = listener.getSslCiphers();

            try {
                // Try to find certificate data on db
                var keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
                final KeyStore keystore;
                if (keystoreContent == null) {
                    if (certificate.isDynamic()) { // fallback to default certificate
                        certificate = currentConfiguration.getCertificates().get(listener.getDefaultCertificate());
                        if (certificate == null) {
                            throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listener.getHost() + ": no default certificate setup.");
                        }
                    }
                    LOG.debug("start SSL with certificate id {}, on listener {}:{} file={}", certificate.getId(), listener.getHost(), port, certificate.getFile());
                    keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
                } else {
                    LOG.debug("start SSL with dynamic certificate id {}, on listener {}:{}", certificate.getId(), listener.getHost(), port);
                    keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
                }
                KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
                keyFactory.init(keystore, certificate.getPassword().toCharArray());

                List<String> ciphers = null;
                if (sslCiphers != null && !sslCiphers.isEmpty()) {
                    LOG.debug("required sslCiphers {}", sslCiphers);
                    ciphers = Arrays.asList(sslCiphers.split(","));
                }
                var sslContext = SslContextBuilder
                        .forServer(keyFactory)
                        .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                        .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                        .sslProvider(SslProvider.OPENSSL)
                        .protocols(listener.getSslProtocols())
                        .ciphers(ciphers).build();

                var chain = readChainFromKeystore(keystore);
                if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                    parent.getOcspStaplingManager().addCertificateForStapling(chain);
                    var attr = sslContext.attributes().attr(AttributeKey.valueOf(OCSP_CERTIFICATE_CHAIN));
                    attr.set(chain[0]);
                }

                return sslContext;
            } catch (IOException | GeneralSecurityException err) {
                LOG.error("ERROR booting listener", err);
                throw new ConfigurationNotValidException(err);
            }
        }
    }

    public SSLCertificateConfiguration chooseCertificate(String sniHostname, String defaultCertificate) {
        if (sniHostname == null) {
            sniHostname = "";
        }
        final var certificates = currentConfiguration.getCertificates();
        SSLCertificateConfiguration certificateMatchExact = null;
        SSLCertificateConfiguration certificateMatchNoExact = null;
        for (final var c : certificates.values()) {
            if (CertificatesUtils.certificateMatches(sniHostname, c, true)) {
                certificateMatchExact = c;
            } else if (CertificatesUtils.certificateMatches(sniHostname, c, false)) {
                if (certificateMatchNoExact == null || c.isMoreSpecific(certificateMatchNoExact)) {
                    certificateMatchNoExact = c;
                }
            }
        }
        SSLCertificateConfiguration chosen = null;
        if (certificateMatchExact != null) {
            chosen = certificateMatchExact;
        } else if (certificateMatchNoExact != null) {
            chosen = certificateMatchNoExact;
        }
        if (chosen == null) {
            chosen = certificates.get(defaultCertificate);
        }
        LOG.info("Resolving SNI for hostname: {}", sniHostname);
        if (chosen == null) {
            LOG.error("No certificate found for SNI hostname: {}", sniHostname);
        } else {
            LOG.info("Using certificate: {}", chosen.getId());
        }
        return chosen;
    }
}
