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
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_COMMIT;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_VERSION;
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
import com.google.common.base.Throwables;
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
import io.prometheus.client.Counter;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.cache.CacheByteBufMemoryUsageMetric;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.user.FileUserRealm;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.carapaceproxy.utils.CarapaceVersion;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.PrometheusUtils;
import org.carapaceproxy.utils.StringUtils;
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

    private static final Counter CONFIGURATION_RELOAD_FAILURES_COUNTER = PrometheusUtils.createCounter(
            "configuration", "reload_failures_total", "failed reloads of the configuration from the store"
    ).register();

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
    private volatile RuntimeServerConfiguration currentConfiguration;

    @Getter
    private ConfigurationStore dynamicConfigurationStore;

    @Getter
    private volatile EndpointMapper mapper;

    @Getter
    @Setter
    private volatile UserRealm realm;

    @Getter
    private final TrustStoreManager trustStoreManager;

    @Getter
    private volatile List<RequestFilter> filters;
    private volatile boolean started;

    @Getter
    private final ByteBufAllocator cachePoolAllocator;
    private final CacheByteBufMemoryUsageMetric cacheByteBufMemoryUsageMetric;
    @Getter
    private final EventLoopGroup eventLoopGroup;

    /**
     * How long a writer waits for the cluster store write lock and for {@link #configurationLock}.
     */
    public static final int STORE_WRITE_LOCK_TIMEOUT_SECONDS = 60;

    /**
     * Matches the key of a certificate hostname property, capturing its index.
     */
    public static final Pattern CERTIFICATE_HOSTNAME = Pattern.compile("certificate\\.(\\d+)\\.hostname");

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
        this.backendHealthManager = new BackendHealthManager(currentConfiguration, this.mapper, this.basePath);
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

    /**
     * Apply a change to the current dynamic configuration, read under the write locks.
     *
     * @param function the change to apply
     * @throws ConfigurationNotValidException       if the current configuration cannot be read
     * @throws InterruptedException                 if interrupted while waiting for the locks
     * @throws ConfigurationChangeInProgressException if the locks are not acquired in time
     * @throws IllegalStateException                if the changed configuration is not valid; nothing is written
     */
    public void rewriteConfiguration(final ConfigurationConsumer function) throws ConfigurationNotValidException, InterruptedException, ConfigurationChangeInProgressException {
        underWriteLocks(lock -> {
            final String currentConfiguration = getDynamicConfigurationStore().toStringConfiguration();
            final PropertiesConfigurationStore configurationStore = ConfigResource.buildStore(currentConfiguration);
            function.accept(configurationStore);
            applyDynamicConfiguration(configurationStore, false);
        });
        fireConfigurationChange();
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

        if (realm instanceof SimpleUserRealm) { // a subclass is a stub too
            LOG.warn("userrealm.class={}: the admin API accepts any credentials", userRealmClassname);
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
            // the watch is live now: a restore committed since the boot load is caught here, a later one by the event;
            // no store when started without configureAtBoot
            if (dynamicConfigurationStore != null
                    && !Objects.equals(dynamicConfigurationStore.readStateGeneration(), dynamicConfigurationStore.getLoadedStateGeneration())) {
                reloadConfigurationFromStore();
            }
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
        if (userRealmClassname == null) {
            return null; // no admin API to protect: a missing realm is rejected at boot
        }
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

    /**
     * The configuration keys read only at boot, by {@link #configureAtBoot(ConfigurationStore)} and the database store.
     * <br>
     * A backup never exports them and a restore drops them: they describe this node (ports, cluster, database,
     * credentials), may hold secrets, and changing them in the dynamic store would have no effect anyway.
     */
    private static final Set<String> BOOT_ONLY_KEYS = Set.of(
            "config.type", "mode", "peer.id", "zkAddress", "zkTimeout", "zkSecure",
            "http.admin.enabled", "http.admin.host", "http.admin.port",
            "https.admin.port", "https.admin.sslcertfile", "https.admin.sslcertfilepassword",
            "admin.advertised.host", "admin.accesslog.path", "admin.accesslog.format.timezone", "admin.accesslog.retention.days",
            "userrealm.class", "aws.accesskey", "aws.secretkey", "listener.offset.port", "replication.factor",
            PrometheusMetricsProvider.PROMETHEUS_STATS_HTTP_ENABLE, PrometheusMetricsProvider.PROMETHEUS_STATS_HTTP_ADDRESS,
            PrometheusMetricsProvider.PROMETHEUS_STATS_HTTP_PORT, PrometheusMetricsProvider.PROMETHEUS_STATS_LATENCY_ROLLOVER_SECONDS
    );

    /**
     * The prefixes of the configuration keys read only at boot: the database datasource (admin password included)
     * and the ZooKeeper client properties. See {@link #BOOT_ONLY_KEYS}.
     */
    private static final List<String> BOOT_ONLY_PREFIXES = List.of("db.", "zookeeper.");

    /**
     * Whether a configuration key is read only at boot, so that it never belongs to a backup or a restore.
     *
     * @param key the configuration key
     * @return whether the key is in {@link #BOOT_ONLY_KEYS} or starts with one of {@link #BOOT_ONLY_PREFIXES}
     */
    private static boolean isBootOnlyKey(final String key) {
        return BOOT_ONLY_KEYS.contains(key) || BOOT_ONLY_PREFIXES.stream().anyMatch(key::startsWith);
    }

    /**
     * Copy a snapshot without the boot-only configuration keys.
     *
     * @param snapshot the snapshot
     * @return the snapshot without the keys of {@link #isBootOnlyKey(String)}
     */
    private static StoreSnapshot withoutBootOnlyKeys(final StoreSnapshot snapshot) {
        final Map<String, String> config = new TreeMap<>(snapshot.config());
        config.keySet().removeIf(HttpProxyServer::isBootOnlyKey);
        return new StoreSnapshot(config, snapshot.keyPairs(), snapshot.acmeAccountKeys(), snapshot.certificates());
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
        userRealmClassname = properties.getClassname("userrealm.class", null);
        if (adminServerEnabled && userRealmClassname == null) {
            throw new ConfigurationNotValidException("userrealm.class is required when the admin API is enabled; "
                    + "use " + FileUserRealm.class.getName() + " to authenticate against a users file, "
                    + "or see the Admin authentication section of the README for the other realms");
        }

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

    /**
     * Store a dynamic certificate and add or update its entry in the configuration, under the write locks.
     *
     * @param cert the certificate to store
     * @throws Exception if the locks are not acquired in time or the certificate cannot be stored or applied
     */
    public void updateDynamicCertificateForDomain(CertificateData cert) throws Exception {
        underWriteLocks(lock -> {
            // Certificate saving on db
            dynamicConfigurationStore.saveCertificate(cert);

            // Configuration updating
            Properties props = dynamicConfigurationStore.asProperties(null);
            boolean newCertificate = !dynamicConfigurationStore.anyPropertyMatches((k, v) -> {
                if (CERTIFICATE_HOSTNAME.matcher(k).matches() && v.equals(cert.getDomain())) {
                    performCertificateUpdate(props, k, cert); // updating existing certificate
                    return true;
                }
                return false;
            });
            if (newCertificate) {
                performCertificateCreate(props, cert);
            }

            // Configuration reloading
            applyDynamicConfiguration(new PropertiesConfigurationStore(props), false);
        });
        fireConfigurationChange();
    }

    /**
     * Enable or disable the maintenance mode, under the write locks.
     *
     * @param value whether the maintenance mode is enabled
     * @throws ConfigurationChangeInProgressException if the locks are not acquired in time
     * @throws InterruptedException                 if interrupted while waiting for the locks
     */
    public void updateMaintenanceMode(boolean value) throws ConfigurationChangeInProgressException, InterruptedException {
        underWriteLocks(lock -> {
            Properties props = dynamicConfigurationStore.asProperties(null);
            props.setProperty("carapace.maintenancemode.enabled", String.valueOf(value));
            applyDynamicConfiguration(new PropertiesConfigurationStore(props), false);
        });
        fireConfigurationChange();
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
     * @param newConfigurationStore the configuration to apply and commit
     * @throws InterruptedException                 if interrupted while waiting for the locks
     * @throws ConfigurationChangeInProgressException if the locks are not acquired in time
     * @see #buildValidConfiguration(org.carapaceproxy.configstore.ConfigurationStore)
     */
    public void applyDynamicConfigurationFromAPI(ConfigurationStore newConfigurationStore) throws InterruptedException, ConfigurationChangeInProgressException {
        underWriteLocks(lock -> applyDynamicConfiguration(newConfigurationStore, false));
        fireConfigurationChange();
    }

    /**
     * Trigger a reload on the other peers. Call it after the write locks are released, once per write.
     */
    private void fireConfigurationChange() {
        groupMembershipHandler.fireEvent("configurationChange", null);
    }

    /**
     * A write to the configuration store, run by {@link #underWriteLocks(StoreWrite)}.
     *
     * @param <E> the checked exception thrown by the write
     */
    @FunctionalInterface
    private interface StoreWrite<E extends Exception> {

        /**
         * Runs the write.
         *
         * @param lock the held cluster store write lock, to check right before a commit
         * @throws E                                    if the write fails
         * @throws InterruptedException                 if interrupted
         * @throws ConfigurationChangeInProgressException if a nested lock is not acquired in time
         */
        void run(GroupMembershipHandler.StoreWriteLock lock) throws E, InterruptedException, ConfigurationChangeInProgressException;
    }

    /**
     * Run a write holding the cluster store write lock and then {@link #configurationLock}, in this order.
     * Both are reentrant, so a write may call another write on the same thread.
     *
     * @param write the write to run
     * @param <E>   the checked exception thrown by the write
     * @throws E                                    if the write fails
     * @throws InterruptedException                 if interrupted while waiting for the locks
     * @throws ConfigurationChangeInProgressException if the store does not answer, or a lock is not acquired within
     *                                              {@link #STORE_WRITE_LOCK_TIMEOUT_SECONDS}
     */
    private <E extends Exception> void underWriteLocks(StoreWrite<E> write) throws E, InterruptedException, ConfigurationChangeInProgressException {
        // a store that hangs, as with the database leader down, would hold the cluster lock and stall every peer
        // a store that hangs after this check still holds the lock: bounding every statement would close that
        try {
            dynamicConfigurationStore.checkAnswering();
        } catch (ConfigurationNotValidException e) {
            throw new ConfigurationChangeInProgressException("configuration store is not answering, try again later: " + e.getMessage());
        }
        final GroupMembershipHandler.StoreWriteLock storeWriteLock;
        try {
            storeWriteLock = groupMembershipHandler.acquireStoreWriteLock(STORE_WRITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ConfigurationChangeInProgressException("configuration store is busy, try again later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        try (storeWriteLock) {
            if (!configurationLock.tryLock(STORE_WRITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new ConfigurationChangeInProgressException("configuration store is busy, try again later");
            }
            try {
                // nobody else can commit now, so the base and the keys to delete are computed on fresh data
                dynamicConfigurationStore.reload();
                write.run(storeWriteLock);
            } finally {
                configurationLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Run a body holding the certificate renewal mutex, so that no renewal writes certificates meanwhile.
     * {@link GroupMembershipHandler#executeInMutex(String, int, Runnable)} skips the body silently when the mutex
     * is busy and swallows its exceptions, hence the explicit outcome.
     *
     * @param body the body to run
     * @throws InterruptedException                 if interrupted while waiting for the mutex
     * @throws ConfigurationChangeInProgressException if the mutex is not acquired in time
     * @throws RuntimeException                     whatever the body throws
     */
    private void underRenewalMutex(Runnable body) throws InterruptedException, ConfigurationChangeInProgressException {
        final AtomicBoolean ran = new AtomicBoolean();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        groupMembershipHandler.executeInMutex(DynamicCertificatesManager.THREAD_NAME, STORE_WRITE_LOCK_TIMEOUT_SECONDS, () -> {
            ran.set(true);
            try {
                body.run();
            } catch (RuntimeException err) {
                failure.set(err);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        if (!ran.get()) {
            // executeInMutex swallows the interruption and only keeps the flag
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted while waiting for the certificate renewal mutex");
            }
            throw new ConfigurationChangeInProgressException("certificate renewal in progress, try again later");
        }
    }

    /**
     * The outcome of {@link #backupState()}.
     *
     * @param snapshot                  the whole exported state
     * @param domainsWithoutCertificate the dynamic certificate domains of the exported configuration that have no
     *                                  available certificate: a restore of this backup issues them again
     */
    public record BackupResult(StoreSnapshot snapshot, List<String> domainsWithoutCertificate) {
    }

    /**
     * The outcome of a committed {@link #restoreState(StoreSnapshot, String, String)}.
     *
     * @param ok            whether this node also applied the restored state
     * @param inMemoryOnly  whether the state lives in memory only ({@code config.type=file}): a restart reloads the file
     * @param message       what happened, for the caller
     */
    public record RestoreResult(boolean ok, boolean inMemoryOnly, String message) {
    }

    /**
     * Export the whole dynamic state (configuration, certificates, key pairs, ACME account key) consistently,
     * without the {@linkplain #isBootOnlyKey(String) boot-only keys}.
     * <br>
     * It holds the same locks as a restore, so that a renewal cannot leave a configured domain without its
     * certificate row in the middle of the export. It never refuses: the dynamic domains without an available
     * certificate are logged and returned, as restoring such a backup issues them again.
     *
     * @return the exported state and the domains without an available certificate
     * @throws InterruptedException                 if interrupted while waiting for the locks
     * @throws ConfigurationChangeInProgressException if the locks or the renewal mutex are not acquired in time
     */
    public BackupResult backupState() throws InterruptedException, ConfigurationChangeInProgressException {
        final AtomicReference<StoreSnapshot> exported = new AtomicReference<>();
        underWriteLocks(lock -> underRenewalMutex(() -> exported.set(dynamicConfigurationStore.exportState())));
        // file mode keeps the boot configuration in the same store
        final StoreSnapshot snapshot = withoutBootOnlyKeys(exported.get());
        final List<String> withoutCertificate = new ArrayList<>();
        snapshot.config().forEach((key, value) -> {
            final Matcher matcher = CERTIFICATE_HOSTNAME.matcher(key);
            if (!matcher.matches() || value.isBlank()) {
                return;
            }
            final String mode = snapshot.config().getOrDefault("certificate." + matcher.group(1) + ".mode", "static");
            if ("static".equalsIgnoreCase(mode.trim())) {
                return;
            }
            final StoreSnapshot.CertificateRow row = snapshot.certificates().get(value.trim());
            if (row == null || !DynamicCertificateState.AVAILABLE.name().equals(row.state())) {
                withoutCertificate.add(value.trim());
            }
        });
        Collections.sort(withoutCertificate);
        if (!withoutCertificate.isEmpty()) {
            LOG.warn("Backup taken with {} dynamic certificates not available, a restore issues them again: {}",
                    withoutCertificate.size(), withoutCertificate);
        }
        return new BackupResult(snapshot, withoutCertificate);
    }

    /**
     * Replace the whole dynamic state (configuration, certificates, key pairs, ACME account key) with a snapshot.
     * ACME challenge tokens are left untouched; the {@linkplain #isBootOnlyKey(String) boot-only keys} of the snapshot are
     * dropped with a warning.
     * <br>
     * Everything an apply could refuse is checked before writing, since the commit comes first here: the
     * configuration, the wildcard certificates credentials, the static certificate, truststore and backend CA files on this node,
     * and that the restored user realm still accepts the caller. Then the store and, in cluster mode, every peer must be
     * ready. The replacement runs under the cluster store write lock, the configuration lock and the renewal mutex,
     * and is applied locally before releasing them; the other peers reload on the event fired afterwards.
     *
     * @param requested the state to restore
     * @param user      the user asking for the restore
     * @param password  the password of the user
     * @return the outcome; when not {@link RestoreResult#ok() ok} either the state was stored but this node could not
     *         apply it, or the store failed while writing and holds the previous state or exactly the snapshot: repeating
     *         the restore is safe
     * @throws ConfigurationNotValidException       if the snapshot is not valid or the cluster is not ready; nothing was written
     * @throws InterruptedException                 if interrupted while waiting for the locks
     * @throws ConfigurationChangeInProgressException if the locks or the renewal mutex are not acquired in time; nothing was written
     * @throws RuntimeException                     if the locks fail before writing; nothing was written
     */
    public RestoreResult restoreState(StoreSnapshot requested, String user, String password)
            throws ConfigurationNotValidException, InterruptedException, ConfigurationChangeInProgressException {
        final List<String> dropped = requested.config().keySet().stream().filter(HttpProxyServer::isBootOnlyKey).sorted().toList();
        final StoreSnapshot snapshot = withoutBootOnlyKeys(requested);
        if (!dropped.isEmpty()) {
            // names only: the values may be secrets
            LOG.warn("Dropping {} boot-only keys from the restored configuration, they keep their current values on every node: {}",
                    dropped.size(), dropped);
        }
        validateRestore(snapshot, user, password);
        dynamicConfigurationStore.checkWritable();
        checkPeerVersions();

        final boolean inMemoryOnly = dynamicConfigurationStore instanceof PropertiesConfigurationStore;
        final AtomicReference<Exception> applyFailure = new AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean();
        try {
            underWriteLocks(lock -> underRenewalMutex(() -> {
                writing.set(true);
                dynamicConfigurationStore.replaceState(snapshot, lock::ensureHeld);
                try {
                    applyDynamicConfiguration(null, true);
                } catch (Exception err) {
                    if (err instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    applyFailure.set(err);
                }
            }));
        } catch (RuntimeException err) {
            if (!writing.get()) {
                throw err;
            }
            // e.g. the connection dropped on commit: the store holds the previous state or the whole snapshot
            LOG.error("The store failed while writing the restored state, outcome unknown", err);
            fireConfigurationChange();
            // the commit may have landed, the event skips this JVM, and with period=0 the stale check never runs
            reloadConfigurationFromStore();
            return new RestoreResult(false, inMemoryOnly, "The store failed while writing the state, it holds either the previous one "
                    + "or the archive: repeat the restore. " + Throwables.getRootCause(err));
        }
        // the store changed anyway: the peers must reload
        fireConfigurationChange();
        // database mode: every node rewrites its files on the generation change; file mode has no generation
        if (inMemoryOnly) {
            try {
                dynamicCertificatesManager.forceStoreLocalCertificates();
            } catch (RuntimeException err) {
                // the files are a copy: they must not turn a committed restore into a failure
                LOG.error("The state was restored, but the local certificate files could not be written", err);
            }
        }

        if (applyFailure.get() != null) {
            LOG.error("The state was restored, but this node could not apply it", applyFailure.get());
            CONFIGURATION_RELOAD_FAILURES_COUNTER.inc();
            return new RestoreResult(false, inMemoryOnly, "The state was restored, but this node could not apply it: "
                    + Throwables.getRootCause(applyFailure.get()));
        }
        LOG.info("State restored by user {}", user);
        return new RestoreResult(true, inMemoryOnly, inMemoryOnly
                ? "The state was restored in memory only: a restart reloads the configuration file"
                : "The state was restored");
    }

    /**
     * Check everything a restore could fail on after its commit, so that a wrong snapshot is refused untouched.
     *
     * @param snapshot the state to restore
     * @param user     the user asking for the restore
     * @param password the password of the user
     * @throws ConfigurationNotValidException naming the first problem found
     */
    private void validateRestore(StoreSnapshot snapshot, String user, String password) throws ConfigurationNotValidException {
        snapshot.validate();
        final Properties properties = new Properties();
        properties.putAll(snapshot.config());
        final PropertiesConfigurationStore store = new PropertiesConfigurationStore(properties);
        final RuntimeServerConfiguration configuration = buildValidConfiguration(store);
        dynamicCertificatesManager.checkWildcardCredentials(configuration.getCertificates());
        // static certificates are only loaded at the first handshake, the truststore failure is only logged
        for (SSLCertificateConfiguration certificate : configuration.getCertificates().values()) {
            if (!certificate.isDynamic()) {
                checkKeyStoreFile("certificate " + certificate.getId(), certificate.getFile(), certificate.getPassword());
            }
        }
        checkKeyStoreFile("truststore", configuration.getSslTrustStoreFile(), configuration.getSslTrustStorePassword());
        // the apply loads the backend CA files after the commit here: checked with the same call and arguments
        for (BackendConfiguration backend : buildMapper(configuration.getMapperClassname(), this, store).getBackends().values()) {
            if (!StringUtils.isBlank(backend.caCertificatePath())) {
                try {
                    trustStoreManager.buildClientSslContextForCaFile(backend.caCertificatePath(), backend.caCertificatePassword());
                } catch (Exception err) {
                    throw new ConfigurationNotValidException("Cannot load the CA file " + backend.caCertificatePath()
                            + " of backend " + backend.id() + " on this node: " + err);
                }
            }
        }
        final UserRealm newRealm = buildRealm(userRealmClassname, store);
        if (newRealm != null && newRealm.login(user, password) == null) {
            throw new ConfigurationNotValidException("The restored user realm would not accept your credentials");
        }
    }

    private void checkKeyStoreFile(String what, String file, String password) throws ConfigurationNotValidException {
        try {
            CertificatesUtils.loadKeyStoreFromFile(file, password, basePath);
        } catch (Exception err) {
            throw new ConfigurationNotValidException("Cannot load the " + what + " file " + file + " on this node: " + err);
        }
    }

    /**
     * Check that every peer runs this same version, so that no peer reads the restored state with other rules.
     *
     * @throws ConfigurationNotValidException listing the peers on another or an unknown version
     */
    private void checkPeerVersions() throws ConfigurationNotValidException {
        if ("dev".equals(CarapaceVersion.VERSION)) {
            LOG.warn("Running a dev build: the version of the peers is not checked");
            return;
        }
        final List<String> offending = new ArrayList<>();
        for (String peer : groupMembershipHandler.getPeers()) {
            final Map<String, String> info = groupMembershipHandler.loadInfoForPeer(peer);
            if (info == null && !groupMembershipHandler.getPeers().contains(peer)) {
                continue; // the peer left while the list was read
            }
            final String version = info == null ? null : info.get(PROPERTY_PEER_VERSION);
            if (!CarapaceVersion.VERSION.equals(version)) {
                offending.add(peer + " (" + (version == null ? "unknown version" : version) + ")");
            }
        }
        if (!offending.isEmpty()) {
            throw new ConfigurationNotValidException("Every peer must run Carapace " + CarapaceVersion.VERSION
                    + " to restore; on another version: " + String.join(", ", offending));
        }
    }

    /**
     * Reload the dynamic configuration from the store and apply it, waiting for {@link #configurationLock}.
     * Used when another peer changed the store; it never takes the cluster store write lock.
     * A failure is logged and counted, not thrown.
     */
    public void reloadConfigurationFromStore() {
        // blocking: callers run on background threads, and a dropped reload leaves this node stale
        configurationLock.lock();
        try {
            dynamicConfigurationStore.reload();
            applyDynamicConfiguration(null, true);
        } catch (Exception err) {
            if (err instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Cannot reload the configuration from the store", err);
            CONFIGURATION_RELOAD_FAILURES_COUNTER.inc();
        } finally {
            configurationLock.unlock();
        }
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
            // Build everything as locals first; do not touch the four `this.X` view fields yet.
            RuntimeServerConfiguration newConfiguration = buildValidConfiguration(storeWithConfig);
            EndpointMapper newMapper = buildMapper(newConfiguration.getMapperClassname(), this, storeWithConfig);
            UserRealm newRealm = buildRealm(userRealmClassname, storeWithConfig);
            List<RequestFilter> newFilters = buildFilters(newConfiguration);

            // Reload subsystems. Any of these can throw; if it does, the four view fields below
            // are left untouched, so request-time readers keep seeing a coherent old snapshot.
            this.backendHealthManager.reloadConfiguration(newConfiguration, newMapper);
            this.dynamicCertificatesManager.reloadConfiguration(newConfiguration);
            this.trustStoreManager.reloadConfiguration(newConfiguration);
            this.ocspStaplingManager.reloadConfiguration(newConfiguration);
            this.listeners.reloadConfiguration(newConfiguration);
            this.cache.reloadConfiguration(newConfiguration);
            this.requestsLogger.reloadConfiguration(newConfiguration);
            Map<String, BackendConfiguration> currentBackends = mapper != null ? mapper.getBackends() : Collections.emptyMap();
            Map<String, BackendConfiguration> newBackends = newMapper.getBackends();

            if (!newBackends.equals(currentBackends) || isConnectionsConfigurationChanged(newConfiguration)) {
                proxyRequestsManager.reloadConfiguration(newConfiguration, newBackends.values());
            }

            if (!atBoot) {
                dynamicConfigurationStore.commitConfiguration(newConfigurationStore);
            }

            // Commit point: every subsystem has reloaded and persistence succeeded; swap the four
            // view fields back-to-back so the cross-field tearing window observed by readers shrinks
            // from the full subsystem-reload duration to a handful of volatile writes.
            this.filters = newFilters;
            this.realm = newRealm;
            this.mapper = newMapper;
            this.currentConfiguration = newConfiguration;
            // only now: a failure above leaves the state stale, so that the renewal pass asks for the reload again
            this.dynamicCertificatesManager.stateApplied(dynamicConfigurationStore.getLoadedStateGeneration());
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
                zkProperties = new Properties(staticConfiguration.asProperties("zookeeper"));
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
            peerInfo.put(PROPERTY_PEER_VERSION, CarapaceVersion.VERSION);
            peerInfo.put(PROPERTY_PEER_COMMIT, CarapaceVersion.COMMIT);
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

    public BackendConfiguration getBackendConfiguration(final EndpointKey endpointKey) {
        for (BackendConfiguration backend : getMapper().getBackends().values()) {
            if (backend.hostPort().equals(endpointKey)) {
                return backend;
            }
        }
        throw new IllegalStateException("No backend configuration found for " + endpointKey);
    }

    private class ConfigurationChangeCallback implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId, Map<String, Object> data) {
            LOG.info("Configuration changed");
            reloadConfigurationFromStore();
        }

        @Override
        public void reconnected() {
            LOG.info("Configuration listener - reloading configuration after ZK reconnection");
            reloadConfigurationFromStore();
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
