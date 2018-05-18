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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
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
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.impl.ConnectionsManagerImpl;
import nettyhttpproxy.server.cache.ContentsCache;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.mapper.XForwardedForRequestFilter;
import org.apache.bookkeeper.stats.*;
import org.apache.bookkeeper.stats.prometheus.*;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

public class HttpProxyServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpProxyServer.class.getName());

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final EndpointMapper mapper;
    private final List<RequestFilter> filters;
    private final ConnectionsManager connectionsManager;
    private final ContentsCache cache;
    private final StatsLogger mainLogger;
    private final File basePath;
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final List<Channel> listeningChannels = new ArrayList<>();

    private StatsProvider statsProvider;

    public HttpProxyServer(EndpointMapper mapper, File basePath) {
        this.mapper = mapper;
        this.basePath = basePath;
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = statsProvider.getStatsLogger("");
        this.connectionsManager = new ConnectionsManagerImpl(10, 120000, 5000, mainLogger);
        this.filters = new ArrayList<>();
        this.cache = new ContentsCache(mainLogger);
    }

    public HttpProxyServer(String host, int port, EndpointMapper mapper) {
        this(mapper, new File(".").getAbsoluteFile());
        listeners.add(new NetworkListenerConfiguration(host, port));
    }

    public void addListener(NetworkListenerConfiguration listener) {
        listeners.add(listener);
    }

    public void addRequestFilter(RequestFilter filter) {
        filters.add(filter);
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {
            bossGroup = new EpollEventLoopGroup();
            workerGroup = new EpollEventLoopGroup();

            for (NetworkListenerConfiguration listener : listeners) {
                LOG.info("Starting listener at " + listener.getHost() + ":" + listener.getPort() + " ssl:" + listener.isSsl());
                final SslContext sslCtx;
                if (listener.isSsl()) {
                    String sslCertFilePassword = listener.getSslCertificatePassword();
                    String sslCiphers = listener.getSslCiphers();
                    String certificateFile = listener.getSslCertificateFile();
                    File sslCertFile = certificateFile.startsWith("/") ? new File(listener.getSslCertificateFile()) : new File(basePath, listener.getSslCertificateFile());
                    sslCertFile = sslCertFile.getAbsoluteFile();
                    LOG.log(Level.SEVERE, "start SSL with certificate " + sslCertFile);
                    try {
                        KeyManagerFactory keyFactory = initKeyManagerFactory("PKCS12", sslCertFile, sslCertFilePassword);

                        List<String> ciphers = null;
                        if (sslCiphers != null && !sslCiphers.isEmpty()) {
                            LOG.log(Level.SEVERE, "required sslCiphers " + sslCiphers);
                            ciphers = Arrays.asList(sslCiphers.split(","));
                        }
                        sslCtx = SslContextBuilder
                                .forServer(keyFactory)
                                .sslProvider(SslProvider.OPENSSL)
                                .ciphers(ciphers).build();
                    } catch (CertificateException | IOException | KeyStoreException | SecurityException
                            | NoSuchAlgorithmException | UnrecoverableKeyException err) {
                        throw new ConfigurationNotValidException(err);
                    };
                } else {
                    sslCtx = null;
                }
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(EpollServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel channel) throws Exception {
                                if (listener.isSsl()) {
                                    channel.pipeline().addLast(sslCtx.newHandler(channel.alloc()));
                                }
                                channel.pipeline().addLast(new HttpRequestDecoder());
                                channel.pipeline().addLast(new HttpResponseEncoder());
                                channel.pipeline().addLast(
                                        new ClientConnectionHandler(mainLogger, mapper,
                                                connectionsManager,
                                                filters, cache,
                                                channel.remoteAddress(), staticContentsManager));

                            }
                        })
                        .option(ChannelOption.SO_BACKLOG, 128)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);
                listeningChannels.add(b.bind(listener.getHost(), listener.getPort()).sync().channel());
            }
            cache.start();
        } catch (RuntimeException err) {
            close();
            throw err;
        }

    }

    public void startMetrics() throws ConfigurationException {
        PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();
        File config = new File(basePath, "conf/metrics.properties");
        if (config.isFile()) {
            statsProviderConfig.load(config);
        }
        statsProvider.start(statsProviderConfig);
    }

    public int getLocalPort() {
        for (Channel c : listeningChannels) {
            InetSocketAddress addr = (InetSocketAddress) c.localAddress();
            return addr.getPort();
        }
        return -1;
    }

    @Override
    public void close() {
        statsProvider.stop();
        for (Channel channel : listeningChannels) {
            channel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (connectionsManager != null) {
            connectionsManager.close();
        }
        cache.close();
        staticContentsManager.close();
    }

    public ConnectionsManager getConnectionsManager() {
        return connectionsManager;
    }

    public ContentsCache getCache() {
        return cache;
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

    public void configure(Properties properties) throws ConfigurationNotValidException {
        for (int i = 0; i < 100; i++) {
            tryConfigureListener(i, properties);
            tryConfigureFilter(i, properties);
        }
    }

    private void tryConfigureListener(int i, Properties properties) {
        String prefix = "listener." + i + ".";
        String host = properties.getProperty(prefix + "host", "0.0.0.0");
        int port = Integer.parseInt(properties.getProperty(prefix + "port", "0"));
        if (port > 0) {
            boolean ssl = Boolean.parseBoolean(properties.getProperty(prefix + "ssl", "false"));
            String certificateFile = properties.getProperty(prefix + "sslcertfile", "");
            String certificatePassword = properties.getProperty(prefix + "sslcertfilepassword", "");
            String sslciphers = properties.getProperty(prefix + "sslciphers", "");
            NetworkListenerConfiguration config = new NetworkListenerConfiguration(host, port, ssl, certificateFile, certificatePassword, sslciphers);
            addListener(config);
        }
    }

    private void tryConfigureFilter(int i, Properties properties) throws ConfigurationNotValidException {
        String prefix = "filter." + i + ".";
        String type = properties.getProperty(prefix + "type", "").trim();
        if (type.isEmpty()) {
            return;
        }
        LOG.log(Level.INFO, "configure filter type={0}", type);
        switch (type) {
            case "add-x-forwarded-for":
                addRequestFilter(new XForwardedForRequestFilter());
                break;
            default:
                throw new ConfigurationNotValidException("bad filter type '" + type + "' only 'add-x-forwarded-for'");
        }
    }

}
