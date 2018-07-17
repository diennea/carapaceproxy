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
import nettyhttpproxy.configstore.ConfigurationStore;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.cache.ContentsCache;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.RequestFilterConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import static nettyhttpproxy.server.filters.RequestFilterFactory.buildRequestFilter;
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

    private final EndpointMapper mapper;
    private final List<RequestFilter> filters;
    private ConnectionsManager connectionsManager;
    private final Listeners listeners;
    private volatile boolean started;
    private final ContentsCache cache;
    private final StatsLogger mainLogger;

    private final File basePath;
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();
    private final BackendHealthManager backendHealthManager;
    private RuntimeServerConfiguration currentConfiguration;

    private PrometheusMetricsProvider statsProvider;
    private final PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();

    private Server adminserver;
    private boolean adminServerEnabled;
    private int adminServerPort = 8001;
    private String adminServerHost = "localhost";

    public HttpProxyServer(EndpointMapper mapper, File basePath) {
        this.mapper = mapper;
        this.basePath = basePath;
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = statsProvider.getStatsLogger("");

        this.backendHealthManager = new BackendHealthManager(mainLogger);
        this.filters = new ArrayList<>();
        this.cache = new ContentsCache(mainLogger);
        this.currentConfiguration = new RuntimeServerConfiguration();
        this.listeners = new Listeners(this);
    }

    public HttpProxyServer(String host, int port, EndpointMapper mapper) throws ConfigurationNotValidException {
        this(mapper, new File(".").getAbsoluteFile());
        currentConfiguration.addListener(new NetworkListenerConfiguration(host, port));
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
        context.addServlet(new ServletHolder(new PrometheusServlet(statsProvider)), "/metrics");
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
        String metricsUrl = "http://" + adminServerHost + ":" + adminServerPort + "/metrics";
        System.out.println("Base Admin UI url: " + uiUrl);
        System.out.println("Base Admin/API url: " + apiUrl);
        System.out.println("Prometheus Metrics url: " + metricsUrl);

    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {
            started = true;
            bootFilters();
            this.connectionsManager = new ConnectionsManagerImpl(currentConfiguration,
                    mainLogger, backendHealthManager);

            listeners.start();
            cache.start();

            backendHealthManager.start();
        } catch (RuntimeException err) {
            close();
            throw err;
        }

    }

    public void startMetrics() throws ConfigurationException {
        statsProvider.start(statsProviderConfig);
    }

    public int getLocalPort() {
        return listeners.getLocalPort();
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

        listeners.stop();

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

    public void configure(Properties properties) throws ConfigurationNotValidException {
        PropertiesConfigurationStore simpleStore = new PropertiesConfigurationStore(properties);
        configure(simpleStore);
    }

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        currentConfiguration.configure(properties);

        statsProviderConfig.setProperty(PrometheusMetricsProvider.PROMETHEUS_STATS_HTTP_ENABLE, false);
        properties.forEach((String key, String value) -> {
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

    }

    private void bootFilters() throws ConfigurationNotValidException {
        for (RequestFilterConfiguration filterConfig : currentConfiguration.getRequestFilters()) {
            RequestFilter filter = buildRequestFilter(filterConfig);
            filters.add(filter);
        }
    }

    @VisibleForTesting
    public void addRequestFilter(RequestFilterConfiguration filter) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addRequestFilter(filter);
    }

    @VisibleForTesting
    public void addListener(NetworkListenerConfiguration configuration) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addListener(configuration);
    }

    @VisibleForTesting
    public void addCertificate(SSLCertificateConfiguration sslCertificateConfiguration) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addCertificate(sslCertificateConfiguration);
    }

    public EndpointMapper getMapper() {
        return mapper;
    }

    public StatsLogger getMainLogger() {
        return mainLogger;
    }

    public File getBasePath() {
        return basePath;
    }

    public StaticContentsManager getStaticContentsManager() {
        return staticContentsManager;
    }

    public RuntimeServerConfiguration getCurrentConfiguration() {
        return currentConfiguration;
    }

    public List<RequestFilter> getFilters() {
        return filters;
    }

    public Listeners getListeners() {
        return listeners;
    }

}
