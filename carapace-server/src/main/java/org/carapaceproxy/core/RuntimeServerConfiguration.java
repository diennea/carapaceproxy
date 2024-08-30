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

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_BORROW_TIMEOUT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_CONNECT_TIMEOUT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_DISPOSE_TIMEOUT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_IDLE_TIMEOUT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_KEEPALIVE;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_KEEPALIVE_COUNT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_KEEPALIVE_IDLE;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_KEEPALIVE_INTERVAL;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_MAX_CONNECTIONS_PER_ENDPOINT;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_MAX_LIFETIME;
import static org.carapaceproxy.server.config.ConnectionPoolConfiguration.DEFAULT_STUCK_REQUEST_TIMEOUT;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.getDefaultHttpProtocols;
import static org.carapaceproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import java.io.File;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import lombok.Data;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.utils.CarapaceLogger;

/**
 * Implementation of a configuration for the whole server.
 * Among other settings, it defines:
 * <ul>
 *     <li>{@link Listeners HTTP listeners} {@link NetworkListenerConfiguration mapping configuration};</li>
 *     <li>{@link SSLCertificateConfiguration SSL certificates configuration};</li>
 *     <li>{@link RequestFilter filters} {@link RequestFilterConfiguration configuration};</li>
 *     <li>{@link ConnectionPoolConfiguration connection pool configuration}.</li>
 * </ul>
 *
 * @author enrico.olivelli
 */
@Data
public class RuntimeServerConfiguration {

    private static final Logger LOG = Logger.getLogger(RuntimeServerConfiguration.class.getName());

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final Map<String, SSLCertificateConfiguration> certificates = new HashMap<>();
    private final List<RequestFilterConfiguration> requestFilters = new ArrayList<>();
    private final Map<String, ConnectionPoolConfiguration> connectionPools = new HashMap<>();
    private ConnectionPoolConfiguration defaultConnectionPool;

    private int maxConnectionsPerEndpoint = DEFAULT_MAX_CONNECTIONS_PER_ENDPOINT;
    private int idleTimeout = DEFAULT_IDLE_TIMEOUT;
    private int maxLifeTime = DEFAULT_MAX_LIFETIME;
    private int stuckRequestTimeout = DEFAULT_STUCK_REQUEST_TIMEOUT;
    private boolean backendsUnreachableOnStuckRequests = false;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int borrowTimeout = DEFAULT_BORROW_TIMEOUT;
    private int disposeTimeout = DEFAULT_DISPOSE_TIMEOUT; // 5 min
    private int soBacklog = 128;
    private int keepaliveIdle = DEFAULT_KEEPALIVE_IDLE; // sec
    private int keepaliveInterval = DEFAULT_KEEPALIVE_INTERVAL; // sec
    private int keepaliveCount = DEFAULT_KEEPALIVE_COUNT;
    private boolean clientKeepAlive = DEFAULT_KEEPALIVE;
    private boolean serverKeepAlive = DEFAULT_KEEPALIVE;
    private int maxKeepAliveRequests = 1000;
    private long cacheMaxSize = 0;
    private long cacheMaxFileSize = 0;
    private boolean cacheDisabledForSecureRequestsWithoutPublic = false;
    private String mapperClassname;
    private String accessLogPath = "access.log";
    private String accessLogTimestampFormat = "yyyy-MM-dd HH:mm:ss.SSS";
    private String accessLogFormat =
            "[<timestamp>] [<method> <host> <uri>] [uid:<user_id>, sid:<session_id>, ip:<client_ip>] "
            + "server=<server_ip>, act=<action_id>, route=<route_id>, backend=<backend_id>. "
            + "time t=<total_time>ms b=<backend_time>ms, protocol=<http_protocol_version>";
    private int accessLogMaxQueueCapacity = 2000;
    private int accessLogFlushInterval = 5000;
    private int accessLogWaitBetweenFailures = DEFAULT_CONNECT_TIMEOUT;
    private long accessLogMaxSize = 524288000;
    private boolean accessLogAdvancedEnabled = false;
    private int accessLogAdvancedBodySize = 1_000; // bytes
    private String userRealmClassname;
    private int healthProbePeriod = 0;
    private int healthConnectTimeout = 5_000;
    private int dynamicCertificatesManagerPeriod = 0;
    private int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;
    private Set<String> domainsCheckerIPAddresses;
    private Set<String> supportedSSLProtocols = null;
    private int ocspStaplingManagerPeriod = 0;
    private int clientsIdleTimeoutSeconds = 120;
    private int responseCompressionThreshold; // bytes; default (0) enabled for all requests
    private boolean requestCompressionEnabled = true;
    private String sslTrustStoreFile;
    private String sslTrustStorePassword;
    private boolean ocspEnabled = false;
    private int maxHeaderSize = 8_192; //bytes; default 8kb
    private boolean maintenanceModeEnabled = false;
    private boolean http10BackwardCompatibilityEnabled = false;
    private String localCertificatesStorePath;
    private Set<String> localCertificatesStorePeersIds;
    private int maxAttempts = DEFAULT_MAX_CONNECTIONS_PER_ENDPOINT;
    private Set<String> alwaysCachedExtensions = Set.of("png", "gif", "jpg", "jpeg", "js", "css", "woff2", "webp");

    public RuntimeServerConfiguration() {
        defaultConnectionPool = new ConnectionPoolConfiguration(
                "*", "*",
                maxConnectionsPerEndpoint,
                borrowTimeout,
                connectTimeout,
                stuckRequestTimeout,
                idleTimeout,
                maxLifeTime,
                disposeTimeout,
                keepaliveIdle,
                keepaliveInterval,
                keepaliveCount,
                true,
                true
        );
    }

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        LOG.log(Level.INFO, "configuring from {0}", properties);
        this.maxConnectionsPerEndpoint = properties.getInt("connectionsmanager.maxconnectionsperendpoint", maxConnectionsPerEndpoint);
        this.idleTimeout = properties.getInt("connectionsmanager.idletimeout", idleTimeout);
        if (this.idleTimeout <= 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.idleTimeout + "' for connectionsmanager.idletimeout");
        }
        this.maxLifeTime = properties.getInt("connectionsmanager.maxlifetime", maxLifeTime);
        if (this.maxLifeTime <= 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.maxLifeTime + "' for connectionsmanager.maxlifetime");
        }
        this.stuckRequestTimeout = properties.getInt("connectionsmanager.stuckrequesttimeout", stuckRequestTimeout);
        this.backendsUnreachableOnStuckRequests = properties.getBoolean("connectionsmanager.backendsunreachableonstuckrequests", backendsUnreachableOnStuckRequests);
        this.connectTimeout = properties.getInt("connectionsmanager.connecttimeout", connectTimeout);
        this.borrowTimeout = properties.getInt("connectionsmanager.borrowtimeout", borrowTimeout);
        this.disposeTimeout = properties.getInt("connectionsmanager.disposetimeout", disposeTimeout);
        this.keepaliveIdle = properties.getInt("connectionsmanager.keepaliveidle", keepaliveIdle);
        this.keepaliveInterval = properties.getInt("connectionsmanager.keepaliveinterval", keepaliveInterval);
        this.keepaliveCount = properties.getInt("connectionsmanager.keepalivecount", keepaliveCount);
        LOG.log(Level.INFO, "connectionsmanager.maxconnectionsperendpoint={0}", maxConnectionsPerEndpoint);
        LOG.log(Level.INFO, "connectionsmanager.idletimeout={0}", idleTimeout);
        LOG.log(Level.INFO, "connectionsmanager.maxlifetime={0}", maxLifeTime);
        LOG.log(Level.INFO, "connectionsmanager.stuckrequesttimeout={0}", stuckRequestTimeout);
        LOG.log(Level.INFO, "connectionsmanager.backendsunreachableonstuckrequests={0}", backendsUnreachableOnStuckRequests);
        LOG.log(Level.INFO, "connectionsmanager.connecttimeout={0}", connectTimeout);
        LOG.log(Level.INFO, "connectionsmanager.borrowtimeout={0}", borrowTimeout);
        LOG.log(Level.INFO, "connectionsmanager.keepaliveidle={0}", keepaliveIdle);
        LOG.log(Level.INFO, "connectionsmanager.keepaliveinterval={0}", keepaliveInterval);
        LOG.log(Level.INFO, "connectionsmanager.keepalivecount={0}", keepaliveCount);

        this.mapperClassname = properties.getClassname("mapper.class", StandardEndpointMapper.class.getName());
        LOG.log(Level.INFO, "mapper.class={0}", this.mapperClassname);

        this.cacheMaxSize = properties.getLong("cache.maxsize", cacheMaxSize);
        this.cacheMaxFileSize = properties.getLong("cache.maxfilesize", cacheMaxFileSize);
        this.cacheDisabledForSecureRequestsWithoutPublic = properties.getBoolean("cache.requests.secure.disablewithoutpublic", cacheDisabledForSecureRequestsWithoutPublic);
        LOG.log(Level.INFO, "cache.maxsize={0}", cacheMaxSize);
        LOG.log(Level.INFO, "cache.maxfilesize={0}", cacheMaxFileSize);
        LOG.log(Level.INFO, "cache.requests.secure.disablewithoutpublic={0}", cacheDisabledForSecureRequestsWithoutPublic);

        this.accessLogPath = properties.getString("accesslog.path", accessLogPath);
        this.accessLogTimestampFormat = properties.getString("accesslog.format.timestamp", accessLogTimestampFormat);
        this.accessLogFormat = properties.getString("accesslog.format", accessLogFormat);
        this.accessLogMaxQueueCapacity = properties.getInt("accesslog.queue.maxcapacity", accessLogMaxQueueCapacity);
        this.accessLogFlushInterval = properties.getInt("accesslog.flush.interval", accessLogFlushInterval);
        this.accessLogWaitBetweenFailures = properties.getInt("accesslog.failure.wait", accessLogWaitBetweenFailures);
        this.accessLogMaxSize = properties.getLong("accesslog.maxsize", accessLogMaxSize);
        String tsFormatExample;
        try {
            SimpleDateFormat formatter = new SimpleDateFormat(this.accessLogTimestampFormat);
            tsFormatExample = formatter.format(new Timestamp(System.currentTimeMillis()));
        } catch (Exception err) {
            throw new ConfigurationNotValidException("Invalid accesslog.format.timestamp='" + accessLogTimestampFormat + ": " + err);
        }
        LOG.log(Level.INFO, "accesslog.path={0}", accessLogPath);
        LOG.log(Level.INFO, "accesslog.format.timestamp={0} (example: {1})", new Object[]{accessLogTimestampFormat, tsFormatExample});
        LOG.log(Level.INFO, "accesslog.format={0}", accessLogFormat);
        LOG.log(Level.INFO, "accesslog.queue.maxcapacity={0}", accessLogMaxQueueCapacity);
        LOG.log(Level.INFO, "accesslog.flush.interval={0}", accessLogFlushInterval);
        LOG.log(Level.INFO, "accesslog.failure.wait={0}", accessLogWaitBetweenFailures);
        LOG.log(Level.INFO, "accesslog.maxsize={0}", accessLogMaxSize);

        accessLogAdvancedEnabled = properties.getBoolean("accesslog.advanced.enabled", accessLogAdvancedEnabled);
        accessLogAdvancedBodySize = properties.getInt("accesslog.advanced.body.size", accessLogAdvancedBodySize);
        LOG.log(Level.INFO, "accesslog.advanced.enabled={0}", accessLogAdvancedEnabled);
        LOG.log(Level.INFO, "accesslog.advanced.body.size={0}", accessLogAdvancedBodySize);

        configureCertificates(properties);
        configureListeners(properties);
        configureFilters(properties);
        configureConnectionPools(properties);

        healthProbePeriod = properties.getInt("healthmanager.period", 0);
        LOG.log(Level.INFO, "healthmanager.period={0}", healthProbePeriod);
        if (healthProbePeriod <= 0) {
            LOG.warning("BACKEND-HEALTH-MANAGER DISABLED");
        }

        healthConnectTimeout = properties.getInt("healthmanager.connecttimeout", healthConnectTimeout);
        LOG.log(Level.INFO, "healthmanager.connecttimeout={0}", healthConnectTimeout);
        if (healthConnectTimeout < 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.healthConnectTimeout + "' for healthmanager.connecttimeout. ConnectTimeout cannot be negative");
        }

        dynamicCertificatesManagerPeriod = properties.getInt("dynamiccertificatesmanager.period", 0);
        LOG.log(Level.INFO, "dynamiccertificatesmanager.period={0}", dynamicCertificatesManagerPeriod);
        keyPairsSize = properties.getInt("dynamiccertificatesmanager.keypairssize", DEFAULT_KEYPAIRS_SIZE);
        LOG.log(Level.INFO, "dynamiccertificatesmanager.keypairssize={0}", keyPairsSize);

        domainsCheckerIPAddresses = properties.getValues("dynamiccertificatesmanager.domainschecker.ipaddresses");
        LOG.log(Level.INFO, "dynamiccertificatesmanager.domainschecker.ipaddresses={0}", domainsCheckerIPAddresses);

        ocspStaplingManagerPeriod = properties.getInt("ocspstaplingmanager.period", 0);
        LOG.log(Level.INFO, "ocspstaplingmanager.period={0}", ocspStaplingManagerPeriod);

        boolean loggingDebugEnabled = properties.getBoolean("logging.debug.enabled", false);
        CarapaceLogger.setLoggingDebugEnabled(loggingDebugEnabled);
        LOG.log(Level.INFO, "logging.debug.enabled={0}", loggingDebugEnabled);

        clientsIdleTimeoutSeconds = properties.getInt("clients.idle.timeout", clientsIdleTimeoutSeconds);
        LOG.log(Level.INFO, "clients.idle.timeout={0}", clientsIdleTimeoutSeconds);

        responseCompressionThreshold = properties.getInt("response.compression.threshold", responseCompressionThreshold);
        LOG.log(Level.INFO, "response.compression.threshold={0}", responseCompressionThreshold);
        requestCompressionEnabled = properties.getBoolean("request.compression.enabled", requestCompressionEnabled);
        LOG.log(Level.INFO, "request.compression.enabled={0}", requestCompressionEnabled);

        sslTrustStoreFile = properties.getString("truststore.ssltruststorefile", sslTrustStoreFile);
        LOG.log(Level.INFO, "truststore.ssltruststorefile={0}", sslTrustStoreFile);

        sslTrustStorePassword = properties.getString("truststore.ssltruststorepassword", sslTrustStorePassword);
        LOG.log(Level.INFO, "truststore.ssltruststorepassword={0}", sslTrustStorePassword);

        ocspEnabled = properties.getBoolean("ocsp.enabled", ocspEnabled);
        LOG.log(Level.INFO, "ocsp.enabled={0}", ocspEnabled);

        maxHeaderSize = properties.getInt("carapace.maxheadersize", maxHeaderSize);
        if (this.maxHeaderSize <= 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.maxHeaderSize + "' for carapace.maxheadersize");
        }
        LOG.log(Level.INFO, "carapace.maxheadersize={0}", maxHeaderSize);

        maintenanceModeEnabled = properties.getBoolean("carapace.maintenancemode.enabled", maintenanceModeEnabled);
        LOG.log(Level.INFO, "carapace.maintenancemode.enabled={0}", maintenanceModeEnabled);

        http10BackwardCompatibilityEnabled = properties.getBoolean("carapace.http10backwardcompatibility.enabled", http10BackwardCompatibilityEnabled);
        LOG.log(Level.INFO, "carapace.http10backwardcompatibility.enabled={0}", http10BackwardCompatibilityEnabled);

        localCertificatesStorePath = properties.getString("dynamiccertificatesmanager.localcertificates.store.path", localCertificatesStorePath);
        LOG.log(Level.INFO, "dynamiccertificatesmanager.localcertificates.store.path={0}", localCertificatesStorePath);
        if (localCertificatesStorePath != null) {
            var root = new File(localCertificatesStorePath);
            if (!root.canWrite()) {
                throw new ConfigurationNotValidException("Cannot write local certificates to path: " + root.getAbsolutePath());
            }
        }

        // storing enabled for all peers by default
        localCertificatesStorePeersIds = properties.getValues("dynamiccertificatesmanager.localcertificates.peers.ids");
        LOG.log(Level.INFO, "dynamiccertificatesmanager.localcertificates.peers.ids={0}", localCertificatesStorePeersIds);

        maxAttempts = properties.getInt("dynamiccertificatesmanager.errors.maxattempts", maxAttempts);
        LOG.log(Level.INFO, "dynamiccertificatesmanager.errors.maxattempts={0}", maxAttempts);

        alwaysCachedExtensions = properties.getValues("cache.cachealways", alwaysCachedExtensions);
        LOG.log(Level.INFO, "cache.cachealways={0}", alwaysCachedExtensions);
    }

    private void configureCertificates(ConfigurationStore properties) throws ConfigurationNotValidException {
        final var max = properties.findMaxIndexForPrefix("certificate");
        for (int i = 0; i <= max; i++) {
            final var prefix = "certificate." + i + ".";
            final var hostname = properties.getString(prefix + "hostname", "");
            if (!hostname.isEmpty()) {
                final var subjectAltNames = properties.getValues(prefix + "san", null);
                final var file = properties.getString(prefix + "file", "");
                final var pw = properties.getString(prefix + "password", "");
                final var mode = properties.getString(prefix + "mode", "static");
                final var daysBeforeRenewal = properties.getInt(prefix + "daysbeforerenewal", DEFAULT_DAYS_BEFORE_RENEWAL);
                try {
                    final var config = new SSLCertificateConfiguration(hostname, subjectAltNames, file, pw, CertificateMode.valueOf(mode.toUpperCase()));
                    if (config.isAcme()) {
                        config.setDaysBeforeRenewal(daysBeforeRenewal);
                    }
                    LOG.log(Level.INFO, "Configuring SSL certificate {0}: {1}", new Object[]{prefix, config});
                    this.addCertificate(config);
                } catch (IllegalArgumentException e) {
                    throw new ConfigurationNotValidException(
                            "Invalid value of '" + mode + "' for " + prefix + "mode. Supperted ones: static, acme, manual"
                    );
                }
            }
        }
    }

    private void configureListeners(ConfigurationStore properties) throws ConfigurationNotValidException {
        int max = properties.findMaxIndexForPrefix("listener");
        for (int i = 0; i <= max; i++) {
            final var prefix = "listener." + i + ".";
            final var port = properties.getInt(prefix + "port", 0);
            if (port > 0) {
                final var ssl = properties.getBoolean(prefix + "ssl", false);
                this.addListener(new NetworkListenerConfiguration(
                        properties.getString(prefix + "host", "0.0.0.0"),
                        port,
                        ssl,
                        properties.getString(prefix + "sslciphers", ""),
                        properties.getString(prefix + "defaultcertificate", "*"),
                        properties.getValues(prefix + "sslprotocols", DEFAULT_SSL_PROTOCOLS),
                        properties.getInt(prefix + "sobacklog", soBacklog),
                        properties.getBoolean(prefix + "keepalive",  serverKeepAlive),
                        properties.getInt(prefix + "keepaliveidle", keepaliveIdle),
                        properties.getInt(prefix + "keepaliveinterval", keepaliveInterval),
                        properties.getInt(prefix + "keepalivecount", keepaliveCount),
                        properties.getInt(prefix + "maxkeepaliverequests", maxKeepAliveRequests),
                        properties.getString(prefix + "forwarded", DEFAULT_FORWARDED_STRATEGY),
                        properties.getValues(prefix + "trustedips", Set.of()),
                        properties.getValues(prefix + "protocol", getDefaultHttpProtocols(ssl))
                ));
            }
        }
    }

    private void configureFilters(ConfigurationStore properties) throws ConfigurationNotValidException {
        int max = properties.findMaxIndexForPrefix("filter");
        for (int i = 0; i <= max; i++) {
            String prefix = "filter." + i + ".";
            String type = properties.getString(prefix + "type", "");
            if (!type.isEmpty()) {
                Map<String, String> filterConfig = new HashMap<>();
                RequestFilterConfiguration config = new RequestFilterConfiguration(type, filterConfig);
                LOG.log(Level.INFO, "configure filter " + prefix + "type={0}", type);
                properties.forEach(prefix, (k, v) -> {
                    LOG.log(Level.INFO, "{0}={1}", new Object[]{k, v});
                    filterConfig.put(k, v);
                });
                // try to build the filter for validation
                buildRequestFilter(config);
                this.addRequestFilter(config);
            }
        }
    }

    private void configureConnectionPools(ConfigurationStore properties) throws ConfigurationNotValidException {
        int max = properties.findMaxIndexForPrefix("connectionpool");
        for (int i = 0; i <= max; i++) {
            final String prefix = "connectionpool." + i + ".";
            String id = properties.getString(prefix + "id", "");
            if (id.isEmpty()) {
                continue;
            }
            String domain = properties.getString(prefix + "domain", "");
            if (domain.isEmpty()) {
                throw new ConfigurationNotValidException(
                        "Invalid connection pool configuration: domain cannot be empty"
                );
            }
            int maxconnectionsperendpoint = properties.getInt(prefix + "maxconnectionsperendpoint", maxConnectionsPerEndpoint);
            int borrowtimeout = properties.getInt(prefix + "borrowtimeout", borrowTimeout);
            int connecttimeout = properties.getInt(prefix + "connecttimeout", connectTimeout);
            int stuckrequesttimeout = properties.getInt(prefix + "stuckrequesttimeout", stuckRequestTimeout);
            int idletimeout = properties.getInt(prefix + "idletimeout", idleTimeout);
            int maxlifetime = properties.getInt(prefix + "maxlifetime", maxLifeTime);
            int disposetimeout = properties.getInt(prefix + "disposetimeout", disposeTimeout);
            int keepaliveidle = properties.getInt(prefix + "keepaliveidle", keepaliveIdle);
            int keepaliveinterval = properties.getInt(prefix + "keepaliveinterval", keepaliveInterval);
            int keepalivecount = properties.getInt(prefix + "keepalivecount", keepaliveCount);
            boolean enabled = properties.getBoolean(prefix + "enabled", false);
            boolean keepAlive = properties.getBoolean(prefix + "keepalive", true);

            ConnectionPoolConfiguration connectionPool = new ConnectionPoolConfiguration(
                    id,
                    domain,
                    maxconnectionsperendpoint,
                    borrowtimeout,
                    connecttimeout,
                    stuckrequesttimeout,
                    idletimeout,
                    maxlifetime,
                    disposetimeout,
                    keepaliveidle,
                    keepaliveinterval,
                    keepalivecount,
                    keepAlive,
                    enabled
            );
            connectionPools.put(id, connectionPool);
            LOG.log(Level.INFO, "Configured connectionpool." + i + ": {0}", connectionPool);
        }

        // default connection pool
        defaultConnectionPool = new ConnectionPoolConfiguration(
                "*", "*",
                getMaxConnectionsPerEndpoint(),
                getBorrowTimeout(),
                getConnectTimeout(),
                getStuckRequestTimeout(),
                getIdleTimeout(),
                getMaxLifeTime(),
                getDisposeTimeout(),
                getKeepaliveIdle(),
                getKeepaliveInterval(),
                getKeepaliveCount(),
                isClientKeepAlive(),
                true
        );
        LOG.log(Level.INFO, "Configured default connectionpool: {0}", defaultConnectionPool);
    }

    public void addListener(NetworkListenerConfiguration listener) throws ConfigurationNotValidException {
        if (listener.isSsl() && !certificates.containsKey(listener.getDefaultCertificate())) {
            throw new ConfigurationNotValidException(
                    "Listener " + listener.getHost() + ":" + listener.getPort() + ", "
                    + "ssl=" + listener.isSsl() + ", "
                    + "default certificate " + listener.getDefaultCertificate() + " not configured."
            );
        }
        if (listener.isSsl()) {
            try {
                if (supportedSSLProtocols == null) {
                    supportedSSLProtocols = Set.of(SSLContext.getDefault().getSupportedSSLParameters().getProtocols());
                }
                if (!supportedSSLProtocols.containsAll(listener.getSslProtocols())) {
                    throw new ConfigurationNotValidException(
                            "Unsupported SSL Protocols " + listener.getSslProtocols()
                            + " for listener " + listener.getHost() + ":" + listener.getPort()
                    );
                }
            } catch (NoSuchAlgorithmException ex) {
                throw new ConfigurationNotValidException(ex);
            }
        }
        listeners.add(listener);
    }

    public void addCertificate(SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        SSLCertificateConfiguration exists = certificates.put(certificate.getId(), certificate);
        if (exists != null) {
            throw new ConfigurationNotValidException("certificate with id " + certificate.getId() + " already configured");
        }
    }

    void addRequestFilter(RequestFilterConfiguration config) {
        requestFilters.add(config);
    }

    public List<NetworkListenerConfiguration> getListeners() {
        return listeners;
    }

    public Map<String, SSLCertificateConfiguration> getCertificates() {
        return certificates;
    }

    public List<RequestFilterConfiguration> getRequestFilters() {
        return requestFilters;
    }

    NetworkListenerConfiguration getListener(HostPort hostPort) {
        return listeners
                .stream()
                .filter(s -> s.getHost().equalsIgnoreCase(hostPort.host()) && s.getPort() == hostPort.port())
                .findFirst()
                .orElse(null);
    }
}
