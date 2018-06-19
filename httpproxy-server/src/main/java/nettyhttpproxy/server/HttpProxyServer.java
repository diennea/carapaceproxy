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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.DispatcherType;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.api.ApplicationConfig;
import nettyhttpproxy.api.ForceHeadersAPIRequestsFilter;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.impl.ConnectionsManagerImpl;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.cache.ContentsCache;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import nettyhttpproxy.server.filters.RegexpMapSessionIdFilter;
import nettyhttpproxy.server.filters.RegexpMapUserIdFilter;
import nettyhttpproxy.server.mapper.XForwardedForRequestFilter;
import org.apache.bookkeeper.stats.*;
import org.apache.bookkeeper.stats.prometheus.*;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.glassfish.jersey.servlet.ServletContainer;
import static org.glassfish.jersey.servlet.ServletProperties.JAXRS_APPLICATION_CLASS;

public class HttpProxyServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpProxyServer.class.getName());

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final EndpointMapper mapper;
    private final List<RequestFilter> filters;
    private ConnectionsManager connectionsManager;
    private final ContentsCache cache;
    private final StatsLogger mainLogger;
    private final Counter currentClientConnections;
    private final File basePath;
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();
    private final BackendHealthManager backendHealthManager;

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final Map<String, SSLCertificateConfiguration> certificates = new HashMap<>();

    private final Map<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final List<Channel> listeningChannels = new ArrayList<>();

    private StatsProvider statsProvider;
    private final PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();

    private Server adminserver;
    private boolean adminServerEnabled;
    private int adminServerPort = 8001;
    private String adminServerHost = "localhost";
    private int maxConnectionsPerEndpoint = 10;
    private int idleTimeout = 10000;
    private int connectTimeout = 10000;

    public HttpProxyServer(EndpointMapper mapper, File basePath) {
        this.mapper = mapper;
        this.basePath = basePath;
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = statsProvider.getStatsLogger("");
        this.currentClientConnections = mainLogger.getCounter("clients");
        this.backendHealthManager = new BackendHealthManager(mainLogger);
        this.filters = new ArrayList<>();
        this.cache = new ContentsCache(mainLogger);
    }

    public HttpProxyServer(String host, int port, EndpointMapper mapper) {
        this(mapper, new File(".").getAbsoluteFile());
        listeners.add(new NetworkListenerConfiguration(host, port));
    }

    public void addListener(NetworkListenerConfiguration listener) throws ConfigurationNotValidException {
        if (listener.isSsl() && !certificates.containsKey(listener.getDefaultCertificate())) {
            throw new ConfigurationNotValidException("listener " + listener.getHost() + ":" + listener.getPort() + ", ssl=" + listener.isSsl() + ", default certificate " + listener.getDefaultCertificate() + " not configured");
        }
        listeners.add(listener);
    }

    public void addCertificate(SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        SSLCertificateConfiguration exists = certificates.put(certificate.getId(), certificate);
        if (exists != null) {
            throw new ConfigurationNotValidException("certificate with id " + certificate.getId() + " already configured");
        }
    }

    public void addRequestFilter(RequestFilter filter) {
        filters.add(filter);
    }

    public void startAdminInterface() throws Exception {
        if (!adminServerEnabled) {
            return;
        }
        adminserver = new Server(new InetSocketAddress(adminServerHost, adminServerPort));
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        adminserver.setHandler(contexts);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.GZIP);
        context.setContextPath("/");
        context.addFilter(ForceHeadersAPIRequestsFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ServletHolder jerseyServlet = new ServletHolder(new ServletContainer());
        jerseyServlet.setInitOrder(0);
        jerseyServlet.setInitParameter(JAXRS_APPLICATION_CLASS, ApplicationConfig.class.getCanonicalName());
        context.addServlet(jerseyServlet, "/api/*");
        context.setAttribute("server", this);
        contexts.addHandler(context);

        File webUi = new File(basePath, "web/ui");
        if (webUi.isDirectory()) {
            WebAppContext webApp = new WebAppContext(webUi.getAbsolutePath(), "/ui");
            contexts.addHandler(webApp);
        } else {
            System.out.println("Cannot find " + webUi.getAbsolutePath() + " directory. Web UI will not be deployed");
        }

        adminserver.start();
        String apiUrl = "http://" + adminServerHost + ":" + adminServerPort + "/api";
        String uiUrl = "http://" + adminServerHost + ":" + adminServerPort + "/ui";
        System.out.println("Base Admin UI url: " + uiUrl);
        System.out.println("Base Admin/API url: " + apiUrl);

    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {

            this.connectionsManager = new ConnectionsManagerImpl(
                    maxConnectionsPerEndpoint,
                    idleTimeout,
                    connectTimeout,
                    mainLogger, backendHealthManager);
            bossGroup = new EpollEventLoopGroup();
            workerGroup = new EpollEventLoopGroup();

            for (NetworkListenerConfiguration listener : listeners) {
                bootListener(listener);
            }
            cache.start();

            backendHealthManager.start();
        } catch (RuntimeException err) {
            close();
            throw err;
        }

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

    private void bootListener(NetworkListenerConfiguration listener) throws ConfigurationNotValidException, InterruptedException {
        LOG.info("Starting listener at " + listener.getHost() + ":" + listener.getPort() + " ssl:" + listener.isSsl());

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
                                new ClientConnectionHandler(mainLogger, mapper,
                                        connectionsManager,
                                        filters, cache,
                                        channel.remoteAddress(), staticContentsManager,
                                        () -> currentClientConnections.dec(),
                                        backendHealthManager));

                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        listeningChannels.add(b.bind(listener.getHost(), listener.getPort()).sync().channel());
    }

    public void startMetrics() throws ConfigurationException {
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
        backendHealthManager.stop();

        if (adminserver != null) {
            try {
                adminserver.stop();
            } catch (Exception err) {
                LOG.log(Level.SEVERE, "Error while stopping admin server", err);
            } finally {
                adminserver = null;
            }
        }
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

    public BackendHealthManager getBackendHealthManager() {
        return backendHealthManager;
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
            tryConfigureCertificate(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureListener(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureFilter(i, properties);
        }
        properties.forEach((key, value) -> {
            statsProviderConfig.setProperty(key + "", value);
        });
        adminServerEnabled = Boolean.parseBoolean(properties.getProperty("http.admin.enabled", "false"));
        adminServerPort = Integer.parseInt(properties.getProperty("http.admin.port", adminServerPort + ""));
        adminServerHost = properties.getProperty("http.admin.host", adminServerHost);
        LOG.info("http.admin.enabled=" + adminServerEnabled);
        LOG.info("http.admin.port=" + adminServerPort);
        LOG.info("http.admin.host=" + adminServerHost);

        int healthProbePeriod = Integer.parseInt(properties.getProperty("healthmanager.period", "0"));
        LOG.info("healthmanager.period=" + healthProbePeriod);
        backendHealthManager.setPeriod(healthProbePeriod);

        this.maxConnectionsPerEndpoint = Integer.parseInt(properties.getProperty("connectionsmanager.maxconnectionsperendpoint", maxConnectionsPerEndpoint + ""));
        this.idleTimeout = Integer.parseInt(properties.getProperty("connectionsmanager.idletimeout", idleTimeout + ""));
        this.connectTimeout = Integer.parseInt(properties.getProperty("connectionsmanager.connecttimeout", connectTimeout + ""));
        LOG.info("connectionsmanager.maxconnectionsperendpoint=" + maxConnectionsPerEndpoint);
        LOG.info("connectionsmanager.idletimeout=" + idleTimeout);
        LOG.info("connectionsmanager.connecttimeout=" + connectTimeout);

    }

    public int getMaxConnectionsPerEndpoint() {
        return maxConnectionsPerEndpoint;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    private void tryConfigureCertificate(int i,
            Properties properties
    ) throws ConfigurationNotValidException {
        String prefix
                = "certificate." + i
                + ".";

        String certificateHostname
                = properties
                        .getProperty(prefix
                                + "hostname", "");

        if (!certificateHostname
                .isEmpty()) {
            String certificateFile
                    = properties
                            .getProperty(prefix
                                    + "sslcertfile", "");
            String certificatePassword
                    = properties
                            .getProperty(prefix
                                    + "sslcertfilepassword", "");
            LOG
                    .log(Level.INFO,
                            "Configuring SSL certificate {0}hostname={1}, file: {2}", new Object[]{prefix,
                        certificateHostname,
                        certificateFile

                    });

            SSLCertificateConfiguration config
                    = new SSLCertificateConfiguration(certificateHostname,
                            certificateFile,
                            certificatePassword
                    );
            addCertificate(config
            );

        }

    }

    private void tryConfigureListener(int i,
            Properties properties
    ) throws ConfigurationNotValidException {
        String prefix
                = "listener." + i
                + ".";
        String host
                = properties
                        .getProperty(prefix
                                + "host", "0.0.0.0");

        int port
                = Integer
                        .parseInt(properties
                                .getProperty(prefix
                                        + "port", "0"));

        if (port
                > 0) {
            boolean ssl
                    = Boolean
                            .parseBoolean(properties
                                    .getProperty(prefix
                                            + "ssl", "false"));

            boolean ocps
                    = Boolean
                            .parseBoolean(properties
                                    .getProperty(prefix
                                            + "ocps", "true"));
            String trustStoreFile
                    = properties
                            .getProperty(prefix
                                    + "ssltruststorefile", "");
            String trustStorePassword
                    = properties
                            .getProperty(prefix
                                    + "ssltruststorepassword", "");
            String sslciphers
                    = properties
                            .getProperty(prefix
                                    + "sslciphers", "");
            String defautlSslCertificate
                    = properties
                            .getProperty(prefix
                                    + "defaultcertificate", "*");
            NetworkListenerConfiguration config
                    = new NetworkListenerConfiguration(host,
                            port,
                            ssl,
                            ocps,
                            sslciphers,
                            defautlSslCertificate,
                            trustStoreFile,
                            trustStorePassword
                    );
            addListener(config
            );

        }
    }

    private void tryConfigureFilter(int i,
            Properties properties
    ) throws ConfigurationNotValidException {
        String prefix = "filter." + i + ".";
        String type = properties.getProperty(prefix + "type", "").trim();

        if (type.isEmpty()) {
            return;
        }
        LOG.log(Level.INFO, "configure filter " + prefix + "type={0}", type);

        switch (type) {
            case "add-x-forwarded-for":
                addRequestFilter(new XForwardedForRequestFilter());
                break;
            case "match-user-regexp": {
                String param = properties.getProperty(prefix + "param", "userid").trim();
                String regexp = properties.getProperty(prefix + "regexp", "(.*)").trim();
                LOG.log(Level.INFO, prefix + "param" + "=" + param);
                LOG.log(Level.INFO, prefix + "regexp" + "=" + regexp);
                addRequestFilter(new RegexpMapUserIdFilter(param, regexp));
            }
            break;
            case "match-session-regexp": {
                String param = properties.getProperty(prefix + "param", "sid").trim();
                String regexp = properties.getProperty(prefix + "regexp", "(.*)").trim();
                LOG.log(Level.INFO, prefix + "param" + "=" + param);
                LOG.log(Level.INFO, prefix + "regexp" + "=" + regexp);
                addRequestFilter(new RegexpMapSessionIdFilter(param, regexp));
            }
            break;
            default:
                throw new ConfigurationNotValidException("bad filter type '" + type
                        + "' only 'add-x-forwarded-for', 'match-user-regexp'");
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
                                        .getDefaultCertificate() + "', available " + certificates
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

}
