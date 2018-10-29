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
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.DispatcherType;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.api.ApplicationConfig;
import nettyhttpproxy.api.ForceHeadersAPIRequestsFilter;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.impl.ConnectionsManagerImpl;
import nettyhttpproxy.configstore.ConfigurationStore;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.cache.ContentsCache;
import nettyhttpproxy.server.config.ConfigurationChangeInProgressException;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.RequestFilterConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import static nettyhttpproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import nettyhttpproxy.user.UserRealm;
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

    private final Listeners listeners;
    private final ContentsCache cache;
    private final StatsLogger mainLogger;
    private final File basePath;
    private final StaticContentsManager staticContentsManager = new StaticContentsManager();
    private final BackendHealthManager backendHealthManager;
    private final ConnectionsManager connectionsManager;
    private final PrometheusMetricsProvider statsProvider;
    private final PropertiesConfiguration statsProviderConfig = new PropertiesConfiguration();
    private final RequestsLogger requestsLogger;

    private RuntimeServerConfiguration currentConfiguration;
    private EndpointMapper mapper;
    private UserRealm realm;
    private List<RequestFilter> filters;
    private volatile boolean started;

    /**
     * Guards concurrent configuration changes
     */
    private final ReentrantLock configurationLock = new ReentrantLock();

    private Server adminserver;
    private boolean adminServerEnabled;
    private int adminServerPort = 8001;
    private String adminServerHost = "localhost";

    public HttpProxyServer(EndpointMapper mapper, File basePath) {
        this.mapper = mapper;
        this.basePath = basePath;
        this.statsProvider = new PrometheusMetricsProvider();
        this.mainLogger = statsProvider.getStatsLogger("");

        this.filters = new ArrayList<>();
        this.currentConfiguration = new RuntimeServerConfiguration();
        this.backendHealthManager = new BackendHealthManager(currentConfiguration, mapper, mainLogger);
        this.listeners = new Listeners(this);
        this.cache = new ContentsCache(mainLogger, currentConfiguration);
        this.requestsLogger = new RequestsLogger(currentConfiguration);
        this.connectionsManager = new ConnectionsManagerImpl(currentConfiguration,
                mainLogger, backendHealthManager);
    }

    public static HttpProxyServer buildForTests(String host, int port, EndpointMapper mapper) throws ConfigurationNotValidException {
        HttpProxyServer res = new HttpProxyServer(mapper, new File(".").getAbsoluteFile());
        res.currentConfiguration.addListener(new NetworkListenerConfiguration(host, port));
        return res;
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
            LOG.severe("Cannot find " + webUi.getAbsolutePath() + " directory. Web UI will not be deployed");
        }

        adminserver.start();
        String apiUrl = "http://" + adminServerHost + ":" + adminServerPort + "/api";
        String uiUrl = "http://" + adminServerHost + ":" + adminServerPort + "/ui";
        String metricsUrl = "http://" + adminServerHost + ":" + adminServerPort + "/metrics";
        LOG.info("Base Admin UI url: " + uiUrl);
        LOG.info("Base Admin/API url: " + apiUrl);
        LOG.info("Prometheus Metrics url: " + metricsUrl);

    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        try {
            started = true;
            connectionsManager.start();
            cache.start();
            requestsLogger.start();
            listeners.start();
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
        if (requestsLogger != null) {
            requestsLogger.stop();
        }
        if (cache != null) {
            cache.close();
        }
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
    
    private static UserRealm buildRealm(String className, ConfigurationStore properties) throws ConfigurationNotValidException {
        try {
            UserRealm res = (UserRealm) Class.forName(className).getConstructor().newInstance();
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

    /**
     * Configure the service BEFORE starting it.
     *
     * @param properties
     * @throws ConfigurationNotValidException
     * @throws InterruptedException
     * @see
     * #applyDynamicConfiguration(nettyhttpproxy.server.RuntimeServerConfiguration,
     * nettyhttpproxy.configstore.ConfigurationStore)
     */
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException, InterruptedException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        applyStaticConfiguration(properties);

        RuntimeServerConfiguration newConfiguration = buildValidConfiguration(properties);

        try {
            applyDynamicConfiguration(newConfiguration, properties);
        } catch (ConfigurationChangeInProgressException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void applyStaticConfiguration(ConfigurationStore properties) throws NumberFormatException {
        if (started) {
            throw new IllegalStateException("server already started");
        }
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

    public RuntimeServerConfiguration buildValidConfiguration(ConfigurationStore simpleStore) throws ConfigurationNotValidException {
        RuntimeServerConfiguration newConfiguration = new RuntimeServerConfiguration();
        newConfiguration.configure(simpleStore);
        // creating a mapper validates the configuration
        buildMapper(newConfiguration.getMapperClassname(), simpleStore);
        buildRealm(newConfiguration.getUserRealmClassname(), simpleStore);
        
        return newConfiguration;
    }

    /**
     * Apply a new configuration. The configuration MUST be valid
     *
     * @param newConfiguration
     * @param simpleStore
     * @throws InterruptedException
     * @see
     * #buildValidConfiguration(nettyhttpproxy.configstore.ConfigurationStore)
     */
    public void applyDynamicConfiguration(RuntimeServerConfiguration newConfiguration, ConfigurationStore simpleStore)
            throws InterruptedException, ConfigurationChangeInProgressException {
        if (!configurationLock.tryLock()) {
            throw new ConfigurationChangeInProgressException();
        }
        try {
            EndpointMapper newMapper = buildMapper(newConfiguration.getMapperClassname(), simpleStore);
            UserRealm newRealm = buildRealm(newConfiguration.getUserRealmClassname(), simpleStore);
            
            this.filters = buildFilters(newConfiguration);
            this.backendHealthManager.reloadConfiguration(newConfiguration);
            this.listeners.reloadConfiguration(newConfiguration);
            this.cache.reloadConfiguration(newConfiguration);
            this.requestsLogger.reloadConfiguration(newConfiguration);
            this.connectionsManager.applyNewConfiguration(newConfiguration);
            
            this.currentConfiguration = newConfiguration;
            this.mapper = newMapper;
            this.realm = newRealm;
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
    }
    
    @VisibleForTesting
    public void setRealm(UserRealm realm) {
        this.realm = realm;
    }

}
