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

import com.google.common.annotations.VisibleForTesting;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AsyncMapping;
import io.netty.util.concurrent.Promise;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.NetworkListenerConfiguration.HostPort;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.StatsLogger;

/**
 *
 * @author enrico.olivelli
 */
public class Listeners {

    private static final Logger LOG = Logger.getLogger(Listeners.class.getName());
    private final Counter currentClientConnections;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final HttpProxyServer parent;
    private final Map<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final Map<HostPort, Channel> listeningChannels = new ConcurrentHashMap<>();
    private final StatsLogger mainLogger;
    private final File basePath;
    private boolean started;

    private RuntimeServerConfiguration currentConfiguration;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.mainLogger = parent.getMainLogger();
        this.currentClientConnections = mainLogger.getCounter("clients");
        this.currentConfiguration = parent.getCurrentConfiguration();
        this.basePath = parent.getBasePath();
        this.bossGroup = new EpollEventLoopGroup();
        this.workerGroup = new EpollEventLoopGroup();
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadConfiguration(currentConfiguration);
    }

    private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        String sslCiphers = listener.getSslCiphers();

        String trustStrorePassword = listener.getSslTrustorePassword();
        String trustStoreFile = listener.getSslTrustoreFile();
        File trustStoreCertFile = null;
        boolean caFileConfigured = trustStoreFile != null && !trustStoreFile.isEmpty();
        if (caFileConfigured) {
            trustStoreCertFile = trustStoreFile.startsWith("/") ? new File(trustStoreFile) : new File(basePath, trustStoreFile);
            trustStoreCertFile = trustStoreCertFile.getAbsoluteFile();
        }

        String sslCertFilePassword = certificate.getSslCertificatePassword();
        String certificateFile = certificate.getSslCertificateFile();
        File sslCertFile = certificateFile.startsWith("/") ? new File(certificateFile) : new File(basePath, certificateFile);
        sslCertFile = sslCertFile.getAbsoluteFile();

        LOG.log(Level.SEVERE, "start SSL with certificate id " + certificate + ", on listener " + listener.getHost() + ":" + listener.getPort() + " file=" + sslCertFile + " OCPS " + listener.isOcps());
        try {
            KeyManagerFactory keyFactory = initKeyManagerFactory("PKCS12", sslCertFile, sslCertFilePassword);
            TrustManagerFactory trustManagerFactory = null;
            if (caFileConfigured) {
                LOG.log(Level.SEVERE, "loading trustore from " + trustStoreCertFile);
                trustManagerFactory = initTrustManagerFactory("PKCS12", trustStoreCertFile, trustStrorePassword);
            }

            List<String> ciphers = null;
            if (sslCiphers != null && !sslCiphers.isEmpty()) {
                LOG.log(Level.SEVERE, "required sslCiphers " + sslCiphers);
                ciphers = Arrays.asList(sslCiphers.split(","));
            }
            return SslContextBuilder
                    .forServer(keyFactory)
                    .enableOcsp(listener.isOcps() && OpenSsl.isOcspSupported())
                    .trustManager(trustManagerFactory)
                    .sslProvider(SslProvider.OPENSSL)
                    .ciphers(ciphers).build();
        } catch (CertificateException | IOException | KeyStoreException | SecurityException
                | NoSuchAlgorithmException | UnrecoverableKeyException err) {
            throw new ConfigurationNotValidException(err);
        }

    }

    private void bootListener(NetworkListenerConfiguration listener) throws InterruptedException {
        LOG.log(Level.INFO, "Starting listener at {0}:{1} ssl:{2}", new Object[]{listener.getHost(), listener.getPort(), listener.isSsl()});

        AsyncMapping<String, SslContext> sniMappings = (String sniHostname, Promise<SslContext> promise) -> {
            try {
                SslContext sslContext = resolveSslContext(listener, sniHostname);
                return promise.setSuccess(sslContext);
            } catch (ConfigurationNotValidException err) {
                LOG.log(Level.SEVERE, "Error booting certificate for SNI hostname {0}, on listener {1}", new Object[]{sniHostname, listener});
                return promise.setFailure(err);
            }
        };
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(EpollServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        currentClientConnections.inc();
                        if (listener.isSsl()) {
                            SniHandler sni = new SniHandler(sniMappings);
                            channel.pipeline().addLast(sni);
                        }
                        channel.pipeline().addLast(new HttpRequestDecoder());
                        channel.pipeline().addLast(new HttpResponseEncoder());
                        channel.pipeline().addLast(
                                new ClientConnectionHandler(mainLogger, parent.getMapper(),
                                        parent.getConnectionsManager(),
                                        parent.getFilters(), parent.getCache(),
                                        channel.remoteAddress(), parent.getStaticContentsManager(),
                                        () -> currentClientConnections.dec(),
                                        parent.getBackendHealthManager()));

                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        HostPort key = new HostPort(listener.getHost(), listener.getPort());
        Channel channel = b.bind(listener.getHost(), listener.getPort()).sync().channel();

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

    public int getLocalPort() {
        for (Channel c : listeningChannels.values()) {
            InetSocketAddress addr = (InetSocketAddress) c.localAddress();
            return addr.getPort();
        }
        return -1;
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
        String key = listener.getHost() + ":" + listener.getPort() + "+" + sniHostname;

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
                    throw new RuntimeException(err
                    );

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
        SSLCertificateConfiguration certificateMatchExact = null;
        SSLCertificateConfiguration certificateMatchNoExact = null;

        Map<String, SSLCertificateConfiguration> certificates = currentConfiguration.getCertificates();
        for (SSLCertificateConfiguration c : certificates.values()) {
            if (certificateMatches(sniHostname, c, true)) {
                certificateMatchExact = c;
            } else if (certificateMatches(sniHostname, c, false)) {
                certificateMatchNoExact = c;
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

    private boolean certificateMatches(String hostname, SSLCertificateConfiguration c, boolean exact
    ) {
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
            channel
                    .close()
                    .sync();
        }
    }

}
