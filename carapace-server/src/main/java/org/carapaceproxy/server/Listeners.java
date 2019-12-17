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
package org.carapaceproxy.server;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AsyncMapping;
import io.netty.util.concurrent.Promise;
import io.prometheus.client.Gauge;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration.HostPort;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;

/**
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "https://github.com/spotbugs/spotbugs/issues/432")
public class Listeners {

    private static final Logger LOG = Logger.getLogger(Listeners.class.getName());
    private static final Gauge CURRENT_CONNECTED_CLIENTS_GAUGE = PrometheusUtils.createGauge("clients", "current_connected",
            "currently connected clients").register();

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final HttpProxyServer parent;
    private final Map<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final Map<HostPort, Channel> listeningChannels = new ConcurrentHashMap<>();
    private final Map<HostPort, ClientConnectionHandler> listenersHandlers = new ConcurrentHashMap<>();
    private final File basePath;
    private boolean started;

    private RuntimeServerConfiguration currentConfiguration;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.currentConfiguration = parent.getCurrentConfiguration();
        this.basePath = parent.getBasePath();
        if (Epoll.isAvailable()) {
            this.bossGroup = new EpollEventLoopGroup();
            this.workerGroup = new EpollEventLoopGroup();
        } else { // For windows devs
            this.bossGroup = new NioEventLoopGroup();
            this.workerGroup = new NioEventLoopGroup();
        }
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadConfiguration(currentConfiguration);
    }

    private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        int port = listener.getPort() + parent.getListenersOffsetPort();
        String sslCiphers = listener.getSslCiphers();

        String trustStrorePassword = listener.getSslTrustorePassword();
        String trustStoreFile = listener.getSslTrustoreFile();
        File trustStoreCertFile = null;
        boolean caFileConfigured = trustStoreFile != null && !trustStoreFile.isEmpty();
        if (caFileConfigured) {
            trustStoreCertFile = trustStoreFile.startsWith("/") ? new File(trustStoreFile) : new File(basePath, trustStoreFile);
            trustStoreCertFile = trustStoreCertFile.getAbsoluteFile();
        }

        try {
            KeyManagerFactory keyFactory;
            String domain = certificate.getHostname();
            // Try to find certificate data on db
            byte[] keystoreContent = parent.getDynamicCertificateManager().getCertificateForDomain(domain);
            if (keystoreContent != null) {
                LOG.log(Level.INFO, "start SSL with dynamic certificate id " + certificate.getId() + ", on listener " + listener.getHost() + ":" + port + " OCSP " + listener.isOcsp());
                keyFactory = initKeyManagerFactory("PKCS12", keystoreContent, certificate.getPassword());
            } else {
                if (certificate.isDynamic()) { // fallback to default certificate
                    certificate = currentConfiguration.getCertificates().get(listener.getDefaultCertificate());
                    if (certificate == null) {
                        throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listener.getHost() + ": no default certificate setup.");
                    }
                }
                String certificateFile = certificate.getFile();
                File sslCertFile = certificateFile.startsWith("/") ? new File(certificateFile) : new File(basePath, certificateFile);
                sslCertFile = sslCertFile.getAbsoluteFile();
                LOG.log(Level.INFO, "start SSL with certificate id " + certificate.getId() + ", on listener " + listener.getHost() + ":" + port + " file=" + sslCertFile + " OCSP " + listener.isOcsp());
                keyFactory = initKeyManagerFactory("PKCS12", sslCertFile, certificate.getPassword());
            }

            TrustManagerFactory trustManagerFactory = null;
            if (caFileConfigured) {
                LOG.log(Level.INFO, "loading trustore from " + trustStoreCertFile);
                trustManagerFactory = initTrustManagerFactory("PKCS12", trustStoreCertFile, trustStrorePassword);
            }

            List<String> ciphers = null;
            if (sslCiphers != null && !sslCiphers.isEmpty()) {
                LOG.log(Level.INFO, "required sslCiphers " + sslCiphers);
                ciphers = Arrays.asList(sslCiphers.split(","));
            }
            return SslContextBuilder
                            .forServer(keyFactory)
                            .enableOcsp(listener.isOcsp() && OpenSsl.isOcspSupported())
                            .trustManager(trustManagerFactory)
                            .sslProvider(SslProvider.OPENSSL)
                            .protocols(listener.getSslProtocols())
                            .ciphers(ciphers).build();
        } catch (IOException | GeneralSecurityException err) {
            throw new ConfigurationNotValidException(err);
        }

    }

    private void bootListener(NetworkListenerConfiguration listener) throws InterruptedException {
        int port = listener.getPort() + parent.getListenersOffsetPort();
        LOG.log(Level.INFO, "Starting listener at {0}:{1} ssl:{2}", new Object[]{listener.getHost(), port, listener.isSsl()});

        AsyncMapping<String, SslContext> sniMappings = (String sniHostname, Promise<SslContext> promise) -> {
            try {
                SslContext sslContext = resolveSslContext(listener, sniHostname);
                return promise.setSuccess(sslContext);
            } catch (ConfigurationNotValidException err) {
                LOG.log(Level.SEVERE, "Error booting certificate for SNI hostname {0}, on listener {1}", new Object[]{sniHostname, listener});
                return promise.setFailure(err);
            }
        };

        HostPort key = new HostPort(listener.getHost(), port);
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        CURRENT_CONNECTED_CLIENTS_GAUGE.inc();
                        if (listener.isSsl()) {
                            SniHandler sni = new SniHandler(sniMappings);
                            channel.pipeline().addLast(sni);
                        }
                        channel.pipeline().addLast(new HttpRequestDecoder());
                        channel.pipeline().addLast(new HttpResponseEncoder());

                        ClientConnectionHandler connHandler = new ClientConnectionHandler(parent.getMapper(),
                                parent.getConnectionsManager(),
                                parent.getFilters(), parent.getCache(),
                                channel.remoteAddress(), parent.getStaticContentsManager(),
                                () -> CURRENT_CONNECTED_CLIENTS_GAUGE.dec(),
                                parent.getBackendHealthManager(),
                                parent.getRequestsLogger(),
                                listener.getHost(),
                                port,
                                listener.isSsl()
                        );
                        channel.pipeline().addLast(connHandler);

                        listenersHandlers.put(key, connHandler);
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        Channel channel = b.bind(listener.getHost(), port).sync().channel();

        listeningChannels.put(key, channel);
        LOG.log(Level.INFO, "started listener at {0}: {1}", new Object[]{key, channel});

    }

    private KeyManagerFactory initKeyManagerFactory(String keyStoreType, File keyStoreLocation,
            String keyStorePassword) throws SecurityException, KeyStoreException, NoSuchAlgorithmException,
            CertificateException, IOException, UnrecoverableKeyException {
        KeyStore ks = loadKeyStore(keyStoreType, keyStoreLocation, keyStorePassword);
        KeyManagerFactory kmf = KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePassword.toCharArray());
        return kmf;
    }

    private KeyManagerFactory initKeyManagerFactory(String keyStoreType, byte[] keyStoreData,
            String keyStorePassword) throws SecurityException, KeyStoreException, NoSuchAlgorithmException,
            CertificateException, IOException, UnrecoverableKeyException {
        KeyStore ks = loadKeyStore(keyStoreType, keyStoreData, keyStorePassword);
        KeyManagerFactory kmf = KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePassword.toCharArray());
        return kmf;
    }

    private TrustManagerFactory initTrustManagerFactory(String trustStoreType, File trustStoreLocation,
            String trustStorePassword)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, SecurityException {
        TrustManagerFactory tmf;

        // Initialize trust store
        KeyStore ts = loadKeyStore(trustStoreType, trustStoreLocation, trustStorePassword);
        tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        return tmf;
    }

    private static KeyStore loadKeyStore(String keyStoreType, File keyStoreLocation, String keyStorePassword)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        KeyStore ks = KeyStore.getInstance(keyStoreType);
        try (FileInputStream in = new FileInputStream(keyStoreLocation)) {
            ks.load(in, keyStorePassword.trim().toCharArray());
        }
        return ks;
    }

    private static KeyStore loadKeyStore(String keyStoreType, byte[] keyStoreData, String keyStorePassword)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        KeyStore ks = KeyStore.getInstance(keyStoreType);
        try (ByteArrayInputStream is = new ByteArrayInputStream(keyStoreData)) {
            ks.load(is, keyStorePassword.trim().toCharArray());
        }
        return ks;
    }

    public int getLocalPort() {
        for (Channel c : listeningChannels.values()) {
            InetSocketAddress addr = (InetSocketAddress) c.localAddress();
            return addr.getPort();
        }
        return -1;
    }

    public ClientConnectionHandler getListenerHandler(HostPort key) {
        return listenersHandlers.get(key);
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

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private SslContext resolveSslContext(NetworkListenerConfiguration listener, String sniHostname) throws ConfigurationNotValidException {
        int port = listener.getPort() + parent.getListenersOffsetPort();
        String key = listener.getHost() + ":" + port + "+" + sniHostname;
        if (LOG.isLoggable(Level.FINER)) {
            LOG.log(Level.FINER, "resolve SNI mapping " + sniHostname + ", key: " + key);
        }
        try {
            return sslContexts.computeIfAbsent(key, (k) -> {
                try {
                    SSLCertificateConfiguration choosen = chooseCertificate(sniHostname,
                            listener.getDefaultCertificate());

                    if (choosen == null) {
                        throw new ConfigurationNotValidException("cannot find a certificate for snihostname "
                                + sniHostname
                                + ", with default cert for listener as '" + listener
                                        .getDefaultCertificate() + "', available " + currentConfiguration.getCertificates()
                                        .keySet());
                    }
                    return bootSslContext(listener, choosen);
                } catch (ConfigurationNotValidException err) {
                    throw new RuntimeException(err);
                }
            });

        } catch (RuntimeException err) {
            if (err.getCause() instanceof ConfigurationNotValidException) {
                throw (ConfigurationNotValidException) err.getCause();
            } else {
                throw new ConfigurationNotValidException(err);
            }
        }
    }

    @VisibleForTesting
    public SSLCertificateConfiguration chooseCertificate(String sniHostname, String defaultCertificate) {
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
        if (exact) {
            return !c.isWildcard() && hostname.equals(c.getHostname());
        } else {
            return c.isWildcard() && hostname.endsWith(c.getHostname());
        }
    }

    void reloadConfiguration(RuntimeServerConfiguration newConfiguration) throws InterruptedException {
        if (!started) {
            this.currentConfiguration = newConfiguration;
            return;
        }
        // Clear cached ssl contexts
        sslContexts.clear();

        // stop dropped listeners, start new one
        List<HostPort> listenersToStop = new ArrayList<>();
        List<HostPort> listenersToStart = new ArrayList<>();
        List<HostPort> listenersToRestart = new ArrayList<>();
        for (HostPort key : listeningChannels.keySet()) {
            NetworkListenerConfiguration actualListenerConfig = currentConfiguration.getListener(key);

            NetworkListenerConfiguration newConfigurationForListener = newConfiguration.getListener(key);
            if (newConfigurationForListener == null) {
                LOG.log(Level.INFO, "listener: {0} is to be shut down", key);
                listenersToStop.add(key);
            } else if (!newConfigurationForListener.equals(actualListenerConfig)) {
                LOG.log(Level.INFO, "listener: {0} is to be restarted", key);
                listenersToRestart.add(key);
            }
        }
        for (NetworkListenerConfiguration config : newConfiguration.getListeners()) {
            HostPort key = config.getKey();
            if (!listeningChannels.containsKey(key)) {
                LOG.log(Level.INFO, "listener: {0} is to be started", key);
                listenersToStart.add(key);
            }
        }

        try {
            for (HostPort hostport : listenersToStop) {
                LOG.log(Level.INFO, "Stopping {0}", hostport);
                stopListener(hostport);
            }

            for (HostPort hostport : listenersToRestart) {
                LOG.log(Level.INFO, "Restart {0}", hostport);
                stopListener(hostport);
                NetworkListenerConfiguration newConfigurationForListener = newConfiguration.getListener(hostport);
                bootListener(newConfigurationForListener);
            }

            for (HostPort hostport : listenersToStart) {
                LOG.log(Level.INFO, "Starting {0}", hostport);
                NetworkListenerConfiguration newConfigurationForListener = newConfiguration.getListener(hostport);
                bootListener(newConfigurationForListener);
            }

            // apply new configuration, this will affect SSL certificates
            this.currentConfiguration = newConfiguration;
        } catch (InterruptedException stopMe) {
            Thread.currentThread().interrupt();
            throw stopMe;
        }

    }

    private void stopListener(HostPort hostport) throws InterruptedException {
        Channel channel = listeningChannels.remove(hostport);
        if (channel != null) {
            channel.close().sync();
        }
        listenersHandlers.remove(hostport);
    }

}
