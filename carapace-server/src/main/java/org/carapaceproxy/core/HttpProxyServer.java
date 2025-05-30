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

import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_HOST;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_HTTPS_PORT;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_PORT;
import static org.carapaceproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import static org.glassfish.jersey.servlet.ServletProperties.JAXRS_APPLICATION_CLASS;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.PrometheusRenameFilter;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.prometheus.client.exporter.MetricsServlet;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import javax.servlet.DispatcherType;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.stats.prometheus.PrometheusMetricsProvider;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.carapaceproxy.api.ApplicationConfig;
import org.carapaceproxy.api.AuthAPIRequestsFilter;
import org.carapaceproxy.api.ConfigResource;
import org.carapaceproxy.api.ForceHeadersAPIRequestsFilter;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.cluster.impl.NullGroupMembershipHandler;
import org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationConsumer;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.HerdDBConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.cache.CacheByteBufMemoryUsageMetric;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main server implementation of Carapace proxy.
 *
 * @see Listeners The logic handling incomping requests
 * @see RuntimeServerConfiguration The server confingurations, e.g., the route-to-listener mapping
 */
public class HttpProxyServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProxyServer.class);

    @Getter
    private final Listeners listeners;

    @Getter
    private final ContentsCache cache;
    private final StatsLogger mainLogger;

    @Getter
    private final File basePath;

    @Getter
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();

    @Getter
    @Setter
    private BackendHealthManager backendHealthManager;
    private final PrometheusMetricsProvider statsProvider;
    private final PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();
    private final PrometheusMeterRegistry prometheusRegistry;

    @Getter
    private final RequestsLogger requestsLogger;

    @Getter
    private final ProxyRequestsManager proxyRequestsManager;

    @Getter
    private String peerId = "localhost";
    private String zkAddress;
    private Properties zkProperties = new Properties();
    private boolean zkSecure;
    private int zkTimeout;
    private boolean cluster;

    @Getter
    private GroupMembershipHandler groupMembershipHandler = new NullGroupMembershipHandler();

    @Getter
    @Setter
    private DynamicCertificatesManager dynamicCertificatesManager;

    @Getter
    @Setter
    private OcspStaplingManager ocspStaplingManager;

    @Getter
    private RuntimeServerConfiguration currentConfiguration;

    @Getter
    private ConfigurationStore dynamicConfigurationStore;

    @Getter
    private EndpointMapper mapper;

    @Getter
    @Setter
    private UserRealm realm;

    @Getter
    private final TrustStoreManager trustStoreManager;

    @Getter
    private List<RequestFilter> filters;
    private volatile boolean started;

    @Getter
    private final ByteBufAllocator cachePoolAllocator;
    private final CacheByteBufMemoryUsageMetric cacheByteBufMemoryUsageMetric;
    @Getter
    private final EventLoopGroup eventLoopGroup;

    /**
     * Guards concurrent configuration changes
     */
    private final ReentrantLock configurationLock = new ReentrantLock();

    private Server adminServer;
    private String adminAccessLogPath = "admin.access.log";
    private String adminAccessLogTimezone = "GMT";
    private int adminLogRetentionDays = 90;
    private boolean adminServerEnabled;
    private int adminServerHttpPort = -1;
    private String adminServerHost = "localhost";
    private String adminAdvertisedServerHost = adminServerHost; // hostname to access API/UI
    private int adminServerHttpsPort = -1;
    private String adminServerCertFile;
    private String adminServerCertFilePwd = "";
    @Getter
    private final boolean usePooledByteBufAllocator;
    @Getter
    private String metricsUrl;
    private String userRealmClassname;

    /**
     * This is only for testing cluster mode with a single machine
     */
    @Getter
    private int listenersOffsetPort = 0;

    @VisibleForTesting
    public static HttpProxyServer buildForTests(
            final String host,
            final int port,
            final EndpointMapper.Factory mapperFactory,
            final File baseDir
    ) throws ConfigurationNotValidException {
        final HttpProxyServer server = new HttpProxyServer(mapperFactory, baseDir.getAbsoluteFile());
        final EndpointMapper mapper = server.getMapper();
        server.currentConfiguration.addListener(NetworkListenerConfiguration.withDefault(host, port));
        server.proxyRequestsManager.reloadConfiguration(server.currentConfiguration, mapper.getBackends().values());
        return server;
    }

    public HttpProxyServer(final EndpointMapper.Factory mapperFactory, File basePath) throws ConfigurationNotValidException {
        // metrics
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = this.statsProvider.getStatsLogger("");
        this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.prometheusRegistry.config().meterFilter(new PrometheusRenameFilter());
        Metrics.globalRegistry.add(this.prometheusRegistry);
        Metrics.globalRegistry.config()
                .meterFilter(MeterFilter.denyNameStartsWith(("reactor.netty.http.server.data"))) // spam
                .meterFilter(MeterFilter.denyNameStartsWith(("reactor.netty.http.server.response"))) // spam
                .meterFilter(MeterFilter.denyNameStartsWith(("reactor.netty.http.server.errors"))); // spam

        this.basePath = basePath;
        this.filters = new ArrayList<>();
        this.currentConfiguration = new RuntimeServerConfiguration();
        this.listeners = new Listeners(this);
        this.cache = new ContentsCache(currentConfiguration);
        this.requestsLogger = new RequestsLogger(currentConfiguration);
        this.dynamicCertificatesManager = new DynamicCertificatesManager(this);
        this.trustStoreManager = new TrustStoreManager(currentConfiguration, this);
        this.ocspStaplingManager = new OcspStaplingManager(trustStoreManager);
        this.proxyRequestsManager = new ProxyRequestsManager(this);
        this.mapper = mapperFactory.build(this);
        this.backendHealthManager = new BackendHealthManager(currentConfiguration, this.mapper);
        this.proxyRequestsManager.reloadConfiguration(currentConfiguration, this.mapper.getBackends().values());

        this.usePooledByteBufAllocator = Boolean.getBoolean("cache.allocator.usepooledbytebufallocator");
        this.cachePoolAllocator = usePooledByteBufAllocator
                ? new PooledByteBufAllocator(true)
                : new UnpooledByteBufAllocator(true);
        this.cacheByteBufMemoryUsageMetric = new CacheByteBufMemoryUsageMetric(this);
        // Best practice is to reuse EventLoopGroup
        // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#25.0
        this.eventLoopGroup = Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    public void rewriteConfiguration(final ConfigurationConsumer function) throws ConfigurationNotValidException, InterruptedException, ConfigurationChangeInProgressException {
        final String currentConfiguration = getDynamicConfigurationStore().toStringConfiguration();
        final PropertiesConfigurationStore configurationStore = ConfigResource.buildStore(currentConfiguration);
        function.accept(configurationStore);
        applyDynamicConfigurationFromAPI(configurationStore);
    }

    public int getLocalPort() {
        return listeners.getLocalPort();
    }

    public void startAdminInterface() throws Exception {
        if (!adminServerEnabled) {
            return;
        }

        if (adminServerHttpPort < 0 && adminServerHttpsPort < 0) {
            throw new RuntimeException("To enable admin interface at least one between http and https port must be set");
        }

        adminServer = new Server();

        ServerConnector httpConnector = null;
        if (adminServerHttpPort >= 0) {
            LOG.info("Starting Admin UI over HTTP");

            httpConnector = new ServerConnector(adminServer);
            httpConnector.setPort(adminServerHttpPort);
            httpConnector.setHost(adminServerHost);

            adminServer.addConnector(httpConnector);
        }

        ServerConnector httpsConnector = null;
        if (adminServerHttpsPort >= 0) {
            LOG.info("Starting Admin UI over HTTPS");

            File sslCertFile = adminServerCertFile.startsWith("/") ? new File(adminServerCertFile) : new File(basePath, adminServerCertFile);
            sslCertFile = sslCertFile.getAbsoluteFile();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(sslCertFile)) {
                ks.load(in, adminServerCertFilePwd.trim().toCharArray());
            }

            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStore(ks);
            sslContextFactory.setKeyStorePassword(adminServerCertFilePwd);
            sslContextFactory.setKeyManagerPassword(adminServerCertFilePwd);

            HttpConfiguration https = new HttpConfiguration();
            https.setSecurePort(adminServerHttpsPort);
            https.addCustomizer(new SecureRequestCustomizer());

            httpsConnector = new ServerConnector(adminServer,
                    new SslConnectionFactory(sslContextFactory, "http/1.1"),
                    new HttpConnectionFactory(https));
            httpsConnector.setPort(adminServerHttpsPort);
            httpsConnector.setHost(adminServerHost);

            adminServer.addConnector(httpsConnector);
        }

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        adminServer.setHandler(constrainTraceMethod(contexts));

        File webUi = new File(basePath, "web/ui");
        if (webUi.isDirectory()) {
            WebAppContext webApp = new WebAppContext(webUi.getAbsolutePath(), "/ui");
            contexts.addHandler(webApp);
        } else {
            LOG.error("Cannot find {} directory. Web UI will not be deployed", webUi.getAbsolutePath());
        }

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.GZIP);
        context.setAttribute("server", this);
        context.setContextPath("/");
        context.addFilter(AuthAPIRequestsFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(ForceHeadersAPIRequestsFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ServletHolder jerseyServlet = new ServletHolder(new ServletContainer());
        jerseyServlet.setInitOrder(0);
        jerseyServlet.setInitParameter(JAXRS_APPLICATION_CLASS, ApplicationConfig.class.getCanonicalName());
        context.addServlet(jerseyServlet, "/api/*");
        context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        context.addServlet(new ServletHolder(new MetricsServlet(prometheusRegistry.getPrometheusRegistry())), "/micrometrics");

        NCSARequestLog requestLog = new NCSARequestLog();
        requestLog.setFilename(adminAccessLogPath);
        requestLog.setFilenameDateFormat("yyyy-MM-dd");
        requestLog.setRetainDays(adminLogRetentionDays);
        requestLog.setAppend(true);
        requestLog.setExtended(true);
        requestLog.setLogCookies(false);
        requestLog.setLogTimeZone(adminAccessLogTimezone);
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        requestLogHandler.setRequestLog(requestLog);
        requestLogHandler.setHandler(context);

        contexts.addHandler(requestLogHandler);

        adminServer.start();

        LOG.info("Admin UI started");

        if (adminServerHttpPort == 0 && httpConnector != null) {
            adminServerHttpPort = httpConnector.getLocalPort();
        }
        if (adminServerHttpsPort == 0 && httpsConnector != null) {
            adminServerHttpsPort = httpsConnector.getLocalPort();
        }

        if (adminServerHttpPort > 0) {
            LOG.info("Base HTTP Admin UI url: http://{}:{}/ui", adminAdvertisedServerHost, adminServerHttpPort);
            LOG.info("Base HTTP Admin API url: http://{}:{}/api", adminAdvertisedServerHost, adminServerHttpPort);
        }
        if (adminServerHttpsPort > 0) {
            LOG.info("Base HTTPS Admin UI url: https://{}:{}/ui", adminAdvertisedServerHost, adminServerHttpsPort);
            LOG.info("Base HTTPS Admin API url: https://{}:{}/api", adminAdvertisedServerHost, adminServerHttpsPort);
        }

        if (adminServerHttpPort > 0) {
            metricsUrl = "http://" + adminAdvertisedServerHost + ":" + adminServerHttpPort + "/metrics";
        } else {
            metricsUrl = "https://" + adminAdvertisedServerHost + ":" + adminServerHttpsPort + "/metrics";
        }
        LOG.info("Prometheus Metrics url: {}", metricsUrl);

    }

    /**
     * Add constrain to disable http TRACE method
     *
     * @param handler
     */
    private Handler constrainTraceMethod(Handler handler) {
        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);

        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setConstraint(constraint);
        constraintMapping.setMethod(String.valueOf(HttpMethod.TRACE));
        constraintMapping.setPathSpec("/*");

        Constraint omissionConstraint = new Constraint();
        ConstraintMapping omissionMapping = new ConstraintMapping();
        omissionMapping.setConstraint(omissionConstraint);
        omissionMapping.setMethod("*");
        omissionMapping.setPathSpec("/");

        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.addConstraintMapping(constraintMapping);
        securityHandler.addConstraintMapping(omissionMapping);

        securityHandler.setHandler(handler);
        return securityHandler;
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {
            started = true;
            groupMembershipHandler.start();
            cache.start();
            requestsLogger.start();
            listeners.start();
            backendHealthManager.start();
            dynamicCertificatesManager.attachGroupMembershipHandler(groupMembershipHandler);
            dynamicCertificatesManager.start();
            ocspStaplingManager.start();
            cacheByteBufMemoryUsageMetric.start();
            groupMembershipHandler.watchEvent("configurationChange", new ConfigurationChangeCallback());
        } catch (RuntimeException err) {
            close();
            throw err;
        }
    }

    public void startMetrics() {
        statsProvider.start(statsProviderConfig);
        try {
            io.prometheus.client.hotspot.DefaultExports.initialize();
        } catch (IllegalArgumentException exc) {
            //default metrics already initialized...ok
        }
    }

    @Override
    public void close() {
        groupMembershipHandler.stop();
        backendHealthManager.stop();
        dynamicCertificatesManager.stop();
        ocspStaplingManager.stop();
        cacheByteBufMemoryUsageMetric.stop();

        if (adminServer != null) {
            try {
                adminServer.stop();
            } catch (Exception err) {
                LOG.error("Error while stopping admin server", err);
            } finally {
                adminServer = null;
            }
        }
        statsProvider.stop();

        listeners.stop();

        proxyRequestsManager.close();

        if (requestsLogger != null) {
            requestsLogger.stop();
        }
        if (cache != null) {
            cache.close();
        }
        staticContentsManager.close();

        if (dynamicConfigurationStore != null) {
            // this will also shut down embedded database
            dynamicConfigurationStore.close();
        }
    }

    private static EndpointMapper buildMapper(final String className, final HttpProxyServer parent, final ConfigurationStore properties) throws ConfigurationNotValidException {
        try {
            final Class<? extends EndpointMapper> mapperClass = Class.forName(className).asSubclass(EndpointMapper.class);
            final EndpointMapper res = mapperClass.getConstructor(HttpProxyServer.class).newInstance(parent);
            res.configure(properties);
            return res;
        } catch (final ClassNotFoundException | ClassCastException | NoSuchMethodException err) {
            throw new ConfigurationNotValidException(err);
        } catch (final IllegalAccessException | IllegalArgumentException | InstantiationException | SecurityException | InvocationTargetException err) {
            throw new RuntimeException(err);
        }
    }

    private static UserRealm buildRealm(String userRealmClassname, ConfigurationStore properties) throws ConfigurationNotValidException {
        try {
            UserRealm res = (UserRealm) Class.forName(userRealmClassname).getConstructor().newInstance();
            res.configure(properties);
            return res;
        } catch (ClassNotFoundException err) {
            throw new ConfigurationNotValidException(err);
        } catch (IllegalAccessException | IllegalArgumentException
                | InstantiationException | NoSuchMethodException
                | SecurityException | InvocationTargetException err) {
            throw new RuntimeException(err);
        }
    }

    public void configureAtBoot(ConfigurationStore bootConfigurationStore) throws ConfigurationNotValidException, InterruptedException {
        if (started) {
            throw new IllegalStateException("server already started");
        }

        readClusterConfiguration(bootConfigurationStore); // need to be always first thing to do (loads cluster setup)
        String dynamicConfigurationType = bootConfigurationStore.getString("config.type", cluster ? "database" : "file");
        switch (dynamicConfigurationType) {
            case "file":
                // configuration is store on the same file
                this.dynamicConfigurationStore = bootConfigurationStore;
                if (cluster) {
                    throw new IllegalStateException("Cannot use file based configuration in cluster mode");
                }
                break;
            case "database":
                this.dynamicConfigurationStore = new HerdDBConfigurationStore(bootConfigurationStore, cluster, zkAddress, basePath, mainLogger);
                break;
            default:
                throw new ConfigurationNotValidException("invalid config.type='" + dynamicConfigurationType + "', only 'file' and 'database' are supported");
        }

        this.dynamicCertificatesManager.setConfigurationStore(dynamicConfigurationStore);

        // "static" configuration cannot change without a reboot
        applyStaticConfiguration(bootConfigurationStore);
        // need to be done after static configuration loading in order to know peer info
        initGroupMembership();

        try {
            // apply configuration
            // this can cause database configuration to be overwritten with
            // configuration from service configuration file
            applyDynamicConfiguration(null, true);
        } catch (ConfigurationChangeInProgressException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void applyStaticConfiguration(ConfigurationStore properties) throws NumberFormatException, ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        statsProviderConfig.setProperty(PrometheusMetricsProvider.PROMETHEUS_STATS_HTTP_ENABLE, false);
        properties.forEach(statsProviderConfig::setProperty);
        adminServerEnabled = properties.getBoolean("http.admin.enabled", false);
        adminServerHttpPort = properties.getInt("http.admin.port", adminServerHttpPort);
        adminServerHost = properties.getString("http.admin.host", adminServerHost);
        adminServerHttpsPort = properties.getInt("https.admin.port", adminServerHttpsPort);
        adminServerCertFile = properties.getString("https.admin.sslcertfile", adminServerCertFile);
        adminServerCertFilePwd = properties.getString("https.admin.sslcertfilepassword", adminServerCertFilePwd);
        adminAdvertisedServerHost = properties.getString("admin.advertised.host", adminServerHost);
        listenersOffsetPort = properties.getInt("listener.offset.port", listenersOffsetPort);

        adminAccessLogPath = properties.getString("admin.accesslog.path", adminAccessLogPath);
        adminAccessLogTimezone = properties.getString("admin.accesslog.format.timezone", adminAccessLogTimezone);
        adminLogRetentionDays = properties.getInt("admin.accesslog.retention.days", adminLogRetentionDays);
        userRealmClassname = properties.getClassname("userrealm.class", SimpleUserRealm.class.getName());

        LOG.info("http.admin.enabled={}", adminServerEnabled);
        LOG.info("http.admin.port={}", adminServerHttpPort);
        LOG.info("http.admin.host={}", adminServerHost);
        LOG.info("https.admin.port={}", adminServerHttpsPort);
        LOG.info("https.admin.sslcertfile={}", adminServerCertFile);
        LOG.info("admin.advertised.host={}", adminAdvertisedServerHost);
        LOG.info("listener.offset.port={}", listenersOffsetPort);
        LOG.info("userrealm.class={}", userRealmClassname);
        LOG.info("cache.allocator.usepooledbytebufallocator={}", this.usePooledByteBufAllocator);

        String awsAccessKey = properties.getString("aws.accesskey", null);
        LOG.info("aws.accesskey={}", awsAccessKey);
        String awsSecretKey = properties.getString("aws.secretkey", null);
        LOG.info("aws.secretkey={}", awsSecretKey);
        this.dynamicCertificatesManager.initAWSClient(awsAccessKey, awsSecretKey);
    }

    private static List<RequestFilter> buildFilters(RuntimeServerConfiguration currentConfiguration) throws ConfigurationNotValidException {
        final List<RequestFilter> newFilters = new ArrayList<>();
        for (RequestFilterConfiguration filterConfig : currentConfiguration.getRequestFilters()) {
            RequestFilter filter = buildRequestFilter(filterConfig);
            newFilters.add(filter);
        }
        return newFilters;
    }

    @VisibleForTesting
    public void addRequestFilter(RequestFilterConfiguration filter) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addRequestFilter(filter);
        this.filters = buildFilters(currentConfiguration);
    }

    @VisibleForTesting
    public void addListener(NetworkListenerConfiguration configuration) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addListener(configuration);
        try {
            listeners.reloadConfiguration(currentConfiguration);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @VisibleForTesting
    public void addCertificate(SSLCertificateConfiguration sslCertificateConfiguration) throws ConfigurationNotValidException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        currentConfiguration.addCertificate(sslCertificateConfiguration);
        try {
            listeners.reloadConfiguration(currentConfiguration);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public RuntimeServerConfiguration buildValidConfiguration(ConfigurationStore simpleStore) throws ConfigurationNotValidException {
        RuntimeServerConfiguration newConfiguration = new RuntimeServerConfiguration();

        // Try to perform a service configuration from the passed store.
        newConfiguration.configure(simpleStore);
        buildMapper(newConfiguration.getMapperClassname(), this, simpleStore);
        buildRealm(userRealmClassname, simpleStore);

        return newConfiguration;
    }

    public void updateDynamicCertificateForDomain(CertificateData cert) throws Exception {
        // Certificate saving on db
        dynamicConfigurationStore.saveCertificate(cert);

        // Configuration updating
        Properties props = dynamicConfigurationStore.asProperties(null);
        boolean newCertificate = !dynamicConfigurationStore.anyPropertyMatches((k, v) -> {
            if (k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(cert.getDomain())) {
                performCertificateUpdate(props, k, cert); // updating existing certificate
                return true;
            }
            return false;
        });
        if (newCertificate) {
            performCertificateCreate(props, cert);
        }

        // Configuration reloading
        applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(props));
    }

    public void updateMaintenanceMode(boolean value) throws ConfigurationChangeInProgressException, InterruptedException {
        Properties props = dynamicConfigurationStore.asProperties(null);
        props.setProperty("carapace.maintenancemode.enabled", String.valueOf(value));
        applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(props));
    }

    private void performCertificateUpdate(Properties props, String key, CertificateData cert) {
        props.setProperty(key.replace("hostname", "mode"), cert.isManual() ? "manual" : "acme");
        if (cert.isManual()) {
            props.remove(key.replace("hostname", "daysbeforerenewal")); // type changed from acme to manual
        } else {
            props.setProperty(key.replace("hostname", "daysbeforerenewal"), cert.getDaysBeforeRenewal() + "");
        }
        if (cert.getSubjectAltNames() != null && !cert.getSubjectAltNames().isEmpty()) {
            props.setProperty(
                    key.replace("hostname", "san"),
                    String.join(",", cert.getSubjectAltNames())
            );
        } else {
            props.remove(key.replace("hostname", "san"));
        }
    }

    private void performCertificateCreate(Properties props, CertificateData cert) {
        int index = dynamicConfigurationStore.findMaxIndexForPrefix("certificate") + 1;
        String prefix = "certificate." + index + ".";
        props.setProperty(prefix + "hostname", cert.getDomain());
        props.setProperty(prefix + "mode", cert.isManual() ? "manual" : "acme");
        if (!cert.isManual()) {
            props.setProperty(prefix + "daysbeforerenewal", cert.getDaysBeforeRenewal() + "");
        }
        if (cert.getSubjectAltNames() != null && !cert.getSubjectAltNames().isEmpty()) {
            props.setProperty(prefix + "san", String.join(",", cert.getSubjectAltNames()));
        }
    }

    /**
     * Apply a new configuration. The configuration MUST be valid
     *
     * @param newConfigurationStore
     * @throws InterruptedException
     * @throws org.carapaceproxy.server.config.ConfigurationChangeInProgressException
     * @see #buildValidConfiguration(org.carapaceproxy.configstore.ConfigurationStore)
     */
    public void applyDynamicConfigurationFromAPI(ConfigurationStore newConfigurationStore) throws InterruptedException, ConfigurationChangeInProgressException {
        applyDynamicConfiguration(newConfigurationStore, false);

        // this will trigger a reload on other peers
        groupMembershipHandler.fireEvent("configurationChange", null);
    }

    private void applyDynamicConfiguration(ConfigurationStore newConfigurationStore, boolean atBoot) throws InterruptedException, ConfigurationChangeInProgressException {
        if (atBoot && newConfigurationStore != null) {
            throw new IllegalStateException();
        }
        if (!atBoot && newConfigurationStore == null) {
            throw new IllegalStateException();
        }
        // at boot we are constructing a configuration from the database
        // if the system is already "up" we have to only apply the new config
        ConfigurationStore storeWithConfig = atBoot ? dynamicConfigurationStore : newConfigurationStore;
        if (!configurationLock.tryLock()) {
            throw new ConfigurationChangeInProgressException();
        }
        try {
            RuntimeServerConfiguration newConfiguration = buildValidConfiguration(storeWithConfig);
            EndpointMapper newMapper = buildMapper(newConfiguration.getMapperClassname(), this, storeWithConfig);
            UserRealm newRealm = buildRealm(userRealmClassname, storeWithConfig);

            this.filters = buildFilters(newConfiguration);
            this.backendHealthManager.reloadConfiguration(newConfiguration, newMapper);
            this.dynamicCertificatesManager.reloadConfiguration(newConfiguration);
            this.trustStoreManager.reloadConfiguration(newConfiguration);
            this.ocspStaplingManager.reloadConfiguration(newConfiguration);
            this.listeners.reloadConfiguration(newConfiguration);
            this.cache.reloadConfiguration(newConfiguration);
            this.requestsLogger.reloadConfiguration(newConfiguration);
            this.realm = newRealm;
            Map<String, BackendConfiguration> currentBackends = mapper != null ? mapper.getBackends() : Collections.emptyMap();
            Map<String, BackendConfiguration> newBackends = newMapper.getBackends();
            this.mapper = newMapper;

            if (!newBackends.equals(currentBackends) || isConnectionsConfigurationChanged(newConfiguration)) {
                proxyRequestsManager.reloadConfiguration(newConfiguration, newBackends.values());
            }

            if (!atBoot) {
                dynamicConfigurationStore.commitConfiguration(newConfigurationStore);
            }

            this.currentConfiguration = newConfiguration;
        } catch (ConfigurationNotValidException err) {
            // impossible to have a non valid configuration here
            throw new IllegalStateException(err);
        } finally {
            configurationLock.unlock();
        }
    }

    private boolean isConnectionsConfigurationChanged(RuntimeServerConfiguration newConfiguration) {
        return newConfiguration.getMaxConnectionsPerEndpoint() != currentConfiguration.getMaxConnectionsPerEndpoint()
                || newConfiguration.getBorrowTimeout() != currentConfiguration.getBorrowTimeout()
                || newConfiguration.getConnectTimeout() != currentConfiguration.getConnectTimeout()
                || newConfiguration.getStuckRequestTimeout() != currentConfiguration.getStuckRequestTimeout()
                || newConfiguration.getIdleTimeout() != currentConfiguration.getIdleTimeout()
                || newConfiguration.getMaxLifeTime() != currentConfiguration.getMaxLifeTime()
                || !newConfiguration.getConnectionPools().equals(currentConfiguration.getConnectionPools());
    }

    private void readClusterConfiguration(ConfigurationStore staticConfiguration) throws ConfigurationNotValidException {
        String mode = staticConfiguration.getString("mode", "standalone");
        switch (mode) {
            case "cluster":
                cluster = true;
                peerId = staticConfiguration.getString("peer.id", computeDefaultPeerId());
                zkAddress = staticConfiguration.getString("zkAddress", "localhost:2181");
                zkSecure = staticConfiguration.getBoolean("zkSecure", false);
                zkTimeout = staticConfiguration.getInt("zkTimeout", 40_000);
                zkProperties = new Properties(staticConfiguration.asProperties("zookeeper."));
                LOG.info("mode=cluster, zkAddress=\"{}\", zkTimeout={}, peer.id=\"{}\", zkSecure: {}", zkAddress, zkTimeout, peerId, zkSecure);
                zkProperties.forEach((k, v) -> LOG.info("additional zkclient property: {}={}", k, v));
                break;
            case "standalone":
                cluster = false;
                LOG.info("mode=standalone");
                break;
            default:
                throw new ConfigurationNotValidException("Invalid mode '" + mode + "', only 'cluster' or 'standalone'");
        }
    }

    private void initGroupMembership() {
        if (cluster) {
            Map<String, String> peerInfo = new HashMap<>();
            peerInfo.put(PROPERTY_PEER_ADMIN_SERVER_HOST, adminAdvertisedServerHost);
            peerInfo.put(PROPERTY_PEER_ADMIN_SERVER_PORT, adminServerHttpPort + "");
            peerInfo.put(PROPERTY_PEER_ADMIN_SERVER_HTTPS_PORT, adminServerHttpsPort + "");
            this.groupMembershipHandler = new ZooKeeperGroupMembershipHandler(zkAddress, zkTimeout, zkSecure, peerId, peerInfo, zkProperties);
        } else {
            this.groupMembershipHandler = new NullGroupMembershipHandler();
        }
    }

    private static String computeDefaultPeerId() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException err) {
            // this should not happen on a reverse-proxy
            throw new RuntimeException(err);
        }
    }

    private class ConfigurationChangeCallback implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId, Map<String, Object> data) {
            LOG.info("Configuration changed");
            try {
                dynamicConfigurationStore.reload();
                applyDynamicConfiguration(null, true);
            } catch (Throwable err) {
                LOG.error("Cannot apply new configuration", err);
            }
        }

        @Override
        public void reconnected() {
            LOG.info("Configuration listener - reloading configuration after ZK reconnection");
            try {
                applyDynamicConfiguration(null, true);
            } catch (Throwable err) {
                LOG.error("Cannot apply new configuration", err);
            }
        }

    }

    @Data
    public static class ConnectionPoolStats {

        private int totalConnections; // The number of all connections, active or idle
        private int activeConnections; // The number of the connections that have been successfully acquired and are in active use
        private int idleConnections; // The number of the idle connections
        private int pendingConnections; // The number of requests that are waiting for a connection

    }

    public Map<EndpointKey, Map<String, ConnectionPoolStats>> getConnectionPoolsStats() {
        Map<EndpointKey, Map<String, ConnectionPoolStats>> res = new HashMap<>();
        prometheusRegistry.forEachMeter(meter -> {
            Meter.Id metric = meter.getId();
            if (!metric.getName().startsWith(CONNECTION_PROVIDER_PREFIX)) {
                return;
            }
            final String remoteAddress = Objects.requireNonNull(metric.getTag(REMOTE_ADDRESS));
            EndpointKey key = EndpointKey.make(remoteAddress);
            Map<String, ConnectionPoolStats> pools = res.computeIfAbsent(key, k -> new HashMap<>());
            String poolName = metric.getTag(NAME);
            ConnectionPoolStats stats = pools.computeIfAbsent(poolName, k -> new ConnectionPoolStats());
            double value = 0;
            for (Measurement m : meter.measure()) {
                value += m.getValue();
            }
            switch (metric.getName()) {
                case CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS:
                    stats.totalConnections += (int) value;
                    break;
                case CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS:
                    stats.activeConnections += (int) value;
                    break;
                case CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS:
                    stats.idleConnections += (int) value;
                    break;
                case CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS:
                    stats.pendingConnections += (int) value;
                    break;
            }
        });

        return res;
    }

}
