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
import io.prometheus.client.exporter.MetricsServlet;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.ConfigurationException;
import javax.servlet.DispatcherType;
import org.apache.bookkeeper.stats.*;
import org.apache.bookkeeper.stats.prometheus.*;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.carapaceproxy.EndpointMapper;
import org.carapaceproxy.api.ApplicationConfig;
import org.carapaceproxy.api.AuthAPIRequestsFilter;
import org.carapaceproxy.api.ForceHeadersAPIRequestsFilter;
import org.carapaceproxy.client.ConnectionsManager;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.cluster.impl.NullGroupMembershipHandler;
import org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_HOST;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_PORT;
import static org.carapaceproxy.cluster.impl.ZooKeeperGroupMembershipHandler.PROPERTY_PEER_ADMIN_SERVER_HTTPS_PORT;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.getClassname;
import org.carapaceproxy.configstore.HerdDBConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.cache.ContentsCache;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.glassfish.jersey.servlet.ServletContainer;
import static org.glassfish.jersey.servlet.ServletProperties.JAXRS_APPLICATION_CLASS;

public class HttpProxyServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpProxyServer.class.getName());

    private final Listeners listeners;
    private final ContentsCache cache;
    private final StatsLogger mainLogger;
    private final File basePath;
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();
    private BackendHealthManager backendHealthManager;
    private final ConnectionsManager connectionsManager;
    private final PrometheusMetricsProvider statsProvider;
    private final PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();
    private final RequestsLogger requestsLogger;

    private String peerId = "localhost";
    private String zkAddress;
    private Properties zkProperties = new Properties();
    private boolean zkSecure;
    private int zkTimeout;
    private boolean cluster;
    private GroupMembershipHandler groupMembershipHandler = new NullGroupMembershipHandler();
    private DynamicCertificatesManager dynamicCertificateManager;
    private RuntimeServerConfiguration currentConfiguration;
    private ConfigurationStore dynamicConfigurationStore;
    private EndpointMapper mapper;
    private UserRealm realm;
    private List<RequestFilter> filters;
    private volatile boolean started;

    /**
     * Guards concurrent configuration changes
     */
    private final ReentrantLock configurationLock = new ReentrantLock();

    private Server adminserver;
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
    private String metricsUrl;
    private String userRealmClassname;
    /**
     * This is only for testing cluster mode with a single machine
     */
    private int listenersOffsetPort = 0;

    public HttpProxyServer(EndpointMapper mapper, File basePath) throws Exception {
        this.mapper = mapper;
        this.basePath = basePath;
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = statsProvider.getStatsLogger("");

        this.filters = new ArrayList<>();
        this.currentConfiguration = new RuntimeServerConfiguration();
        this.backendHealthManager = new BackendHealthManager(currentConfiguration, mapper);
        this.listeners = new Listeners(this);
        this.cache = new ContentsCache(currentConfiguration);
        this.requestsLogger = new RequestsLogger(currentConfiguration);
        this.connectionsManager = new ConnectionsManagerImpl(currentConfiguration, backendHealthManager);
        this.dynamicCertificateManager = new DynamicCertificatesManager();
        if (mapper != null) {
            mapper.setDynamicCertificateManager(dynamicCertificateManager);
        }
    }

    public static HttpProxyServer buildForTests(String host, int port, EndpointMapper mapper, File baseDir) throws ConfigurationNotValidException, Exception {
        HttpProxyServer res = new HttpProxyServer(mapper, baseDir.getAbsoluteFile());
        res.currentConfiguration.addListener(new NetworkListenerConfiguration(host, port));
        return res;
    }

    public void startAdminInterface() throws Exception {
        if (!adminServerEnabled) {
            return;
        }

        if (adminServerHttpPort < 0 && adminServerHttpsPort < 0) {
            throw new RuntimeException("To enable admin interface at least one between http and https port must be set");
        }

        adminserver = new Server();

        ServerConnector httpConnector = null;
        if (adminServerHttpPort >= 0) {
            LOG.info("Starting Admin UI over HTTP");

            httpConnector = new ServerConnector(adminserver);
            httpConnector.setPort(adminServerHttpPort);
            httpConnector.setHost(adminServerHost);

            adminserver.addConnector(httpConnector);
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

            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStore(ks);
            sslContextFactory.setKeyStorePassword(adminServerCertFilePwd);
            sslContextFactory.setKeyManagerPassword(adminServerCertFilePwd);

            HttpConfiguration https = new HttpConfiguration();
            https.setSecurePort(adminServerHttpsPort);
            https.addCustomizer(new SecureRequestCustomizer());

            httpsConnector = new ServerConnector(adminserver,
                    new SslConnectionFactory(sslContextFactory, "http/1.1"),
                    new HttpConnectionFactory(https));
            httpsConnector.setPort(adminServerHttpsPort);
            httpsConnector.setHost(adminServerHost);

            adminserver.addConnector(httpsConnector);
        }

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        adminserver.setHandler(contexts);

        File webUi = new File(basePath, "web/ui");
        if (webUi.isDirectory()) {
            WebAppContext webApp = new WebAppContext(webUi.getAbsolutePath(), "/ui");
            contexts.addHandler(webApp);
        } else {
            LOG.severe("Cannot find " + webUi.getAbsolutePath() + " directory. Web UI will not be deployed");
        }

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.GZIP);
        context.setContextPath("/");
        context.addFilter(AuthAPIRequestsFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(ForceHeadersAPIRequestsFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ServletHolder jerseyServlet = new ServletHolder(new ServletContainer());
        jerseyServlet.setInitOrder(0);
        jerseyServlet.setInitParameter(JAXRS_APPLICATION_CLASS, ApplicationConfig.class.getCanonicalName());
        context.addServlet(jerseyServlet, "/api/*");
        context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        context.setAttribute("server", this);

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

        adminserver.start();

        LOG.info("Admin UI started");

        if (adminServerHttpPort == 0 && httpConnector != null) {
            adminServerHttpPort = httpConnector.getLocalPort();
        }
        if (adminServerHttpsPort == 0 && httpsConnector != null) {
            adminServerHttpsPort = httpsConnector.getLocalPort();
        }

        if (adminServerHttpPort > 0) {
            LOG.info("Base HTTP Admin UI url: http://" + adminAdvertisedServerHost + ":" + adminServerHttpPort + "/ui");
            LOG.info("Base HTTP Admin API url: http://" + adminAdvertisedServerHost + ":" + adminServerHttpPort + "/api");
        }
        if (adminServerHttpsPort > 0) {
            LOG.info("Base HTTPS Admin UI url: https://" + adminAdvertisedServerHost + ":" + adminServerHttpsPort + "/ui");
            LOG.info("Base HTTPS Admin API url: https://" + adminAdvertisedServerHost + ":" + adminServerHttpsPort + "/api");
        }

        if (adminServerHttpPort > 0) {
            metricsUrl = "http://" + adminAdvertisedServerHost + ":" + adminServerHttpPort + "/metrics";
        } else {
            metricsUrl = "https://" + adminAdvertisedServerHost + ":" + adminServerHttpsPort + "/metrics";
        }
        LOG.info("Prometheus Metrics url: " + metricsUrl);

    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {
            started = true;
            groupMembershipHandler.start();
            connectionsManager.start();
            cache.start();
            requestsLogger.start();
            listeners.start();
            backendHealthManager.start();
            dynamicCertificateManager.attachGroupMembershipHandler(groupMembershipHandler);
            dynamicCertificateManager.start();
            groupMembershipHandler.watchEvent("configurationChange", new ConfigurationChangeCallback());
        } catch (RuntimeException err) {
            close();
            throw err;
        }
    }

    public void startMetrics() throws ConfigurationException {
        statsProvider.start(statsProviderConfig);
        try {
            io.prometheus.client.hotspot.DefaultExports.initialize();
        } catch (IllegalArgumentException exc) {
            //default metrics already initialized...ok
        }
    }

    public String getMetricsUrl() {
        return metricsUrl;
    }

    public int getLocalPort() {
        return listeners.getLocalPort();
    }

    public int getListenersOffsetPort() {
        return listenersOffsetPort;
    }

    @Override
    public void close() {
        groupMembershipHandler.stop();
        backendHealthManager.stop();
        dynamicCertificateManager.stop();

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
        if (requestsLogger != null) {
            requestsLogger.stop();
        }
        if (cache != null) {
            cache.close();
        }
        staticContentsManager.close();

        if (dynamicConfigurationStore != null) {
            // this will also shutdown embedded database
            dynamicConfigurationStore.close();
        }
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

    public RequestsLogger getRequestsLogger() {
        return requestsLogger;
    }

    private static EndpointMapper buildMapper(String className, ConfigurationStore properties) throws ConfigurationNotValidException {
        try {
            EndpointMapper res = (EndpointMapper) Class.forName(className).getConstructor().newInstance();
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
        String dynamicConfigurationType = bootConfigurationStore.getProperty("config.type", cluster ? "database" : "file");
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

        this.dynamicCertificateManager.setConfigurationStore(dynamicConfigurationStore);

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
        properties.forEach((String key, String value) -> {
            statsProviderConfig.setProperty(key + "", value);
        });
        adminServerEnabled = Boolean.parseBoolean(properties.getProperty("http.admin.enabled", "false"));
        adminServerHttpPort = Integer.parseInt(properties.getProperty("http.admin.port", adminServerHttpPort + ""));
        adminServerHost = properties.getProperty("http.admin.host", adminServerHost);
        adminServerHttpsPort = Integer.parseInt(properties.getProperty("https.admin.port", adminServerHttpsPort + ""));
        adminServerCertFile = properties.getProperty("https.admin.sslcertfile", adminServerCertFile);
        adminServerCertFilePwd = properties.getProperty("https.admin.sslcertfilepassword", adminServerCertFilePwd);
        adminAdvertisedServerHost = properties.getProperty("admin.advertised.host", adminServerHost);
        listenersOffsetPort = Integer.parseInt(properties.getProperty("listener.offset.port", listenersOffsetPort + ""));

        adminAccessLogPath = properties.getProperty("admin.accesslog.path", adminAccessLogPath);
        adminAccessLogTimezone = properties.getProperty("admin.accesslog.format.timezone", adminAccessLogTimezone);
        adminLogRetentionDays = Integer.parseInt(properties.getProperty("admin.accesslog.retention.days", adminLogRetentionDays + ""));

        userRealmClassname = getClassname("userrealm.class", SimpleUserRealm.class.getName(), properties);

        LOG.info("http.admin.enabled=" + adminServerEnabled);
        LOG.info("http.admin.port=" + adminServerHttpPort);
        LOG.info("http.admin.host=" + adminServerHost);
        LOG.info("https.admin.port=" + adminServerHttpsPort);
        LOG.info("https.admin.sslcertfile=" + adminServerCertFile);
        LOG.info("admin.advertised.host=" + adminAdvertisedServerHost);
        LOG.info("listener.offset.port=" + listenersOffsetPort);
        LOG.info("userrealm.class=" + userRealmClassname);
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

    public EndpointMapper getMapper() {
        return mapper;
    }

    public UserRealm getRealm() {
        return realm;
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

    public RuntimeServerConfiguration buildValidConfiguration(ConfigurationStore simpleStore) throws ConfigurationNotValidException {
        RuntimeServerConfiguration newConfiguration = new RuntimeServerConfiguration();

        // Try to perform a service configuration from the passed store.
        newConfiguration.configure(simpleStore);
        buildMapper(newConfiguration.getMapperClassname(), simpleStore);
        buildRealm(userRealmClassname, simpleStore);

        return newConfiguration;
    }

    public void updateDynamicCertificateForDomain(CertificateData cert) throws Exception {
        // Certificate saving on db
        dynamicConfigurationStore.saveCertificate(cert);

        // Configuration updating
        Properties props = dynamicConfigurationStore.asProperties(null);
        boolean certificateForDomainAvailable = !dynamicConfigurationStore.anyPropertyMatches((k, v) -> {
            if (k.matches("certificate\\.[0-9]+\\.hostname") && v.equals(cert.getDomain())) {
                props.setProperty(k.replace("hostname", "mode"), cert.isManual() ? "manual" : "acme"); // updating existing certificate with new type
                return true;
            }
            return false;
        });
        if (certificateForDomainAvailable) {
            String prefix = "certificate." + dynamicConfigurationStore.nextIndexFor("certificate") + ".";
            props.setProperty(prefix + "hostname", cert.getDomain());
            props.setProperty(prefix + "mode", cert.isManual() ? "manual" : "acme");
        }

        // Configuration reloading
        applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(props));
    }

    /**
     * Apply a new configuration. The configuration MUST be valid
     *
     * @param newConfigurationStore
     * @throws InterruptedException
     * @see #buildValidConfiguration(org.carapaceproxy.configstore.ConfigurationStore)
     */
    public void applyDynamicConfigurationFromAPI(ConfigurationStore newConfigurationStore) throws InterruptedException, ConfigurationChangeInProgressException {
        applyDynamicConfiguration(newConfigurationStore, false);

        // this will trigger a reload on other peers
        groupMembershipHandler.fireEvent("configurationChange");
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
            EndpointMapper newMapper = buildMapper(newConfiguration.getMapperClassname(), storeWithConfig);
            newMapper.setDynamicCertificateManager(this.dynamicCertificateManager);
            UserRealm newRealm = buildRealm(userRealmClassname, storeWithConfig);

            this.filters = buildFilters(newConfiguration);
            this.backendHealthManager.reloadConfiguration(newConfiguration, newMapper);
            this.dynamicCertificateManager.reloadConfiguration(newConfiguration);
            this.listeners.reloadConfiguration(newConfiguration);
            this.cache.reloadConfiguration(newConfiguration);
            this.requestsLogger.reloadConfiguration(newConfiguration);
            this.connectionsManager.applyNewConfiguration(newConfiguration);

            this.currentConfiguration = newConfiguration;
            this.mapper = newMapper;
            this.realm = newRealm;

            if (!atBoot) {
                dynamicConfigurationStore.commitConfiguration(newConfigurationStore);
            }

        } catch (ConfigurationNotValidException err) {
            // impossible to have a non valid configuration here
            throw new IllegalStateException(err);
        } finally {
            configurationLock.unlock();
        }
    }

    @VisibleForTesting
    public void setMapper(EndpointMapper mapper) {
        this.mapper = mapper;
        if (mapper != null) {
            mapper.setDynamicCertificateManager(dynamicCertificateManager);
        }
    }

    @VisibleForTesting
    public void setRealm(UserRealm realm) {
        this.realm = realm;
    }

    @VisibleForTesting
    public void setBackendHealthManager(BackendHealthManager backendHealthManager) {
        this.backendHealthManager = backendHealthManager;
    }

    public DynamicCertificatesManager getDynamicCertificateManager() {
        return this.dynamicCertificateManager;
    }

    @VisibleForTesting
    public void setDynamicCertificateManager(DynamicCertificatesManager dynamicCertificateManager) {
        this.dynamicCertificateManager = dynamicCertificateManager;
        if (mapper != null) {
            mapper.setDynamicCertificateManager(dynamicCertificateManager);
        }
    }

    @VisibleForTesting
    public ConfigurationStore getDynamicConfigurationStore() {
        return dynamicConfigurationStore;
    }

    public GroupMembershipHandler getGroupMembershipHandler() {
        return this.groupMembershipHandler;
    }

    private void readClusterConfiguration(ConfigurationStore staticConfiguration) throws ConfigurationNotValidException {
        String mode = staticConfiguration.getProperty("mode", "standalone");
        switch (mode) {
            case "cluster":
                cluster = true;
                peerId = staticConfiguration.getProperty("peer.id", computeDefaultPeerId());
                zkAddress = staticConfiguration.getProperty("zkAddress", "localhost:2181");
                zkSecure = Boolean.parseBoolean(staticConfiguration.getProperty("zkSecure", "false"));
                zkTimeout = Integer.parseInt(staticConfiguration.getProperty("zkTimeout", "40000"));
                zkProperties = new Properties(staticConfiguration.asProperties("zookeeper."));
                LOG.log(Level.INFO, "mode=cluster, zkAddress=''{0}'',zkTimeout={1}, peer.id=''{2}'', zkSecure: {3}", new Object[]{zkAddress, zkTimeout, peerId, zkSecure});
                zkProperties.forEach((k, v) -> {
                    LOG.log(Level.INFO, "additional zkclient property: " + k + "=" + v);
                });
                break;
            case "standalone":
                cluster = false;
                LOG.log(Level.INFO, "mode=standalone");
                break;
            default:
                throw new ConfigurationNotValidException("Invalid mode '" + mode + "', only 'cluster' or 'standalone'");
        }
    }

    private void initGroupMembership() throws ConfigurationNotValidException {
        if (cluster) {
            Map<String, String> peerInfo = new HashMap();
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
        public void eventFired(String eventId) {
            LOG.log(Level.INFO, "Configuration changed");
            try {
                dynamicConfigurationStore.reload();
                applyDynamicConfiguration(null, true);
            } catch (Throwable err) {
                LOG.log(Level.SEVERE, "Cannot apply new configuration", err);
            }
        }

        @Override
        public void reconnected() {
            LOG.log(Level.INFO, "Configuration listener - reloading configuration after ZK reconnection");
            try {
                applyDynamicConfiguration(null, true);
            } catch (Throwable err) {
                LOG.log(Level.SEVERE, "Cannot apply new configuration", err);
            }
        }

    }

}
