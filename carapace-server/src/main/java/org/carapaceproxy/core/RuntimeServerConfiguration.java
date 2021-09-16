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

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.configstore.ConfigurationStore;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode;
import static org.carapaceproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import java.util.Set;
import lombok.Data;
import org.carapaceproxy.utils.CarapaceLogger;

/**
 * Configuration
 *
 * @author enrico.olivelli
 */
@Data
public class RuntimeServerConfiguration {

    private static final Logger LOG = Logger.getLogger(RuntimeServerConfiguration.class.getName());

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final Map<String, SSLCertificateConfiguration> certificates = new HashMap<>();
    private final List<RequestFilterConfiguration> requestFilters = new ArrayList<>();
    private int maxConnectionsPerEndpoint = 10;
    private int idleTimeout = 60000;
    private int stuckRequestTimeout = 120000;
    private boolean backendsUnreachableOnStuckRequests = false;
    private int connectTimeout = 10000;
    private int borrowTimeout = 60000;
    private long cacheMaxSize = 0;
    private long cacheMaxFileSize = 0;
    private boolean cacheDisabledForSecureRequestsWithoutPublic = false;
    private String mapperClassname;
    private String accessLogPath = "access.log";
    private String accessLogTimestampFormat = "yyyy-MM-dd HH:mm:ss.SSS";
    private String accessLogFormat =
            "[<timestamp>] [<method> <host> <uri>] [uid:<user_id>, sid:<session_id>, ip:<client_ip>] "
            + "server=<server_ip>, act=<action_id>, route=<route_id>, backend=<backend_id>. "
            + "time t=<total_time>ms b=<backend_time>ms";
    private int accessLogMaxQueueCapacity = 2000;
    private int accessLogFlushInterval = 5000;
    private int accessLogWaitBetweenFailures = 10000;
    private long accessLogMaxSize = 524288000;
    private boolean accessLogAdvancedEnabled = false;
    private int accessLogAdvancedBodySize = 1_000; // bytes
    private String userRealmClassname;
    private int healthProbePeriod = 0;
    private int dynamicCertificatesManagerPeriod = 0;
    private int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;
    private Set<String> domainsCheckerIPAddresses;
    private List<String> supportedSSLProtocols = null;
    private int ocspStaplingManagerPeriod = 0;
    private boolean requestsHeaderDebugEnabled = false;
    private int clientsIdleTimeoutSeconds = 120;

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        LOG.log(Level.INFO, "configuring from {0}", properties);
        this.maxConnectionsPerEndpoint = properties.getInt("connectionsmanager.maxconnectionsperendpoint", maxConnectionsPerEndpoint);
        this.idleTimeout = properties.getInt("connectionsmanager.idletimeout", idleTimeout);
        if (this.idleTimeout <= 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.idleTimeout + "' for connectionsmanager.idletimeout");
        }
        this.stuckRequestTimeout = properties.getInt("connectionsmanager.stuckrequesttimeout", stuckRequestTimeout);
        this.backendsUnreachableOnStuckRequests = properties.getBoolean("connectionsmanager.backendsunreachableonstuckrequests", backendsUnreachableOnStuckRequests);
        this.connectTimeout = properties.getInt("connectionsmanager.connecttimeout", connectTimeout);
        this.borrowTimeout = properties.getInt("connectionsmanager.borrowtimeout", borrowTimeout);
        LOG.info("connectionsmanager.maxconnectionsperendpoint=" + maxConnectionsPerEndpoint);
        LOG.info("connectionsmanager.idletimeout=" + idleTimeout);
        LOG.info("connectionsmanager.stuckrequesttimeout=" + stuckRequestTimeout);
        LOG.info("connectionsmanager.backendsunreachableonstuckrequests=" + backendsUnreachableOnStuckRequests);
        LOG.info("connectionsmanager.connecttimeout=" + connectTimeout);
        LOG.info("connectionsmanager.borrowtimeout=" + borrowTimeout);

        this.mapperClassname = properties.getClassname("mapper.class", StandardEndpointMapper.class.getName());
        LOG.log(Level.INFO, "mapper.class={0}", this.mapperClassname);

        this.cacheMaxSize = properties.getLong("cache.maxsize", cacheMaxSize);
        this.cacheMaxFileSize = properties.getLong("cache.maxfilesize", cacheMaxFileSize);
        this.cacheDisabledForSecureRequestsWithoutPublic = properties.getBoolean("cache.requests.secure.disablewithoutpublic", cacheDisabledForSecureRequestsWithoutPublic);
        LOG.info("cache.maxsize=" + cacheMaxSize);
        LOG.info("cache.maxfilesize=" + cacheMaxFileSize);
        LOG.info("cache.requests.secure.disablewithoutpublic=" + cacheDisabledForSecureRequestsWithoutPublic);

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
        LOG.info("accesslog.queue.maxcapacity=" + accessLogMaxQueueCapacity);
        LOG.info("accesslog.flush.interval=" + accessLogFlushInterval);
        LOG.info("accesslog.failure.wait=" + accessLogWaitBetweenFailures);
        LOG.info("accesslog.maxsize=" + accessLogMaxSize);

        accessLogAdvancedEnabled = properties.getBoolean("accesslog.advanced.enabled", accessLogAdvancedEnabled);
        accessLogAdvancedBodySize = properties.getInt("accesslog.advanced.body.size", accessLogAdvancedBodySize);
        LOG.info("accesslog.advanced.enabled=" + accessLogAdvancedEnabled);
        LOG.info("accesslog.advanced.body.size=" + accessLogAdvancedBodySize);

        tryConfigureCertificates(properties);
        tryConfigureListeners(properties);
        tryConfigureFilters(properties);

        healthProbePeriod = properties.getInt("healthmanager.period", 0);
        LOG.info("healthmanager.period=" + healthProbePeriod);
        if (healthProbePeriod <= 0) {
            LOG.warning("BACKEND-HEALTH-MANAGER DISABLED");
        }

        dynamicCertificatesManagerPeriod = properties.getInt("dynamiccertificatesmanager.period", 0);
        LOG.info("dynamiccertificatesmanager.period=" + dynamicCertificatesManagerPeriod);
        keyPairsSize = properties.getInt("dynamiccertificatesmanager.keypairssize", DEFAULT_KEYPAIRS_SIZE);
        LOG.info("dynamiccertificatesmanager.keypairssize=" + keyPairsSize);

        domainsCheckerIPAddresses = Set.of(properties.getArray("dynamiccertificatesmanager.domainschecker.ipaddresses", new String[]{}));
        LOG.info("dynamiccertificatesmanager.domainschecker.ipaddresses=" + domainsCheckerIPAddresses);

        ocspStaplingManagerPeriod = properties.getInt("ocspstaplingmanager.period", 0);
        LOG.info("ocspstaplingmanager.period=" + ocspStaplingManagerPeriod);

        boolean loggingDebugEnabled = properties.getBoolean("logging.debug.enabled", false);
        CarapaceLogger.setLoggingDebugEnabled(loggingDebugEnabled);
        LOG.info("logging.debug.enabled=" + loggingDebugEnabled);
        requestsHeaderDebugEnabled = properties.getBoolean("requests.header.debug.enabled", requestsHeaderDebugEnabled);
        LOG.info("requests.header.debug.enabled=" + requestsHeaderDebugEnabled);

        clientsIdleTimeoutSeconds = properties.getInt("clients.idle.timeout", clientsIdleTimeoutSeconds);
        LOG.info("clients.idle.timeout=" + clientsIdleTimeoutSeconds);
    }

    private void tryConfigureCertificates(ConfigurationStore properties) throws ConfigurationNotValidException {
        int max = properties.findMaxIndexForPrefix("certificate");
        for (int i = 0; i <= max; i++) {
            String prefix = "certificate." + i + ".";
            String hostname = properties.getString(prefix + "hostname", "");
            if (!hostname.isEmpty()) {
                String file = properties.getString(prefix + "file", "");
                String pw = properties.getString(prefix + "password", "");
                String mode = properties.getString(prefix + "mode", "static");
                int daysBeforeRenewal = properties.getInt(prefix + "daysbeforerenewal", DEFAULT_DAYS_BEFORE_RENEWAL);
                try {
                    CertificateMode _mode = CertificateMode.valueOf(mode.toUpperCase());
                    LOG.log(Level.INFO,
                            "Configuring SSL certificate {0}: hostname={1}, file={2}, password={3}, mode={4}",
                            new Object[]{prefix, hostname, file, pw, mode}
                    );
                    SSLCertificateConfiguration config = new SSLCertificateConfiguration(hostname, file, pw, _mode);
                    if (config.isAcme()) {
                        config.setDaysBeforeRenewal(daysBeforeRenewal);
                    }
                    this.addCertificate(config);
                } catch (IllegalArgumentException e) {
                    throw new ConfigurationNotValidException(
                            "Invalid value of '" + mode + "' for " + prefix + "mode. Supperted ones: static, acme, manual"
                    );
                }
            }
        }
    }

    private void tryConfigureListeners(ConfigurationStore properties) throws ConfigurationNotValidException {
        int max = properties.findMaxIndexForPrefix("listener");
        for (int i = 0; i <= max; i++) {
            String prefix = "listener." + i + ".";
            String host = properties.getString(prefix + "host", "0.0.0.0");
            int port = properties.getInt(prefix + "port", 0);
            if (port > 0) {
                boolean ssl = properties.getBoolean(prefix + "ssl", false);
                boolean ocsp = properties.getBoolean(prefix + "ocsp", false);
                String trustStoreFile = properties.getString(prefix + "ssltruststorefile", "");
                String trustStorePassword = properties.getString(prefix + "ssltruststorepassword", "");
                String sslciphers = properties.getString(prefix + "sslciphers", "");
                String defautlSslCertificate = properties.getString(prefix + "defaultcertificate", "*");
                NetworkListenerConfiguration config = new NetworkListenerConfiguration(
                        host, port, ssl, ocsp, sslciphers, defautlSslCertificate, trustStoreFile, trustStorePassword
                );
                if (ssl) {
                    config.setSslProtocols(properties.getArray(prefix + "sslprotocols", DEFAULT_SSL_PROTOCOLS.toArray(new String[0])));
                }
                this.addListener(config);
            }
        }
    }

    private void tryConfigureFilters(ConfigurationStore properties) throws ConfigurationNotValidException {
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

    public void addListener(NetworkListenerConfiguration listener) throws ConfigurationNotValidException {
        if (listener.isSsl() && !certificates.containsKey(listener.getDefaultCertificate())) {
            throw new ConfigurationNotValidException("listener " + listener.getHost() + ":" + listener.getPort() + ", ssl=" + listener.isSsl() + ", default certificate " + listener.
                    getDefaultCertificate() + " not configured");
        }
        if (listener.isSsl()) {
            try {
                if (supportedSSLProtocols == null) {
                    supportedSSLProtocols = Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getProtocols());
                }
                if (!supportedSSLProtocols.containsAll(Arrays.asList(listener.getSslProtocols()))) {
                    throw new ConfigurationNotValidException(
                            "Unsupported SSL Protocols " + Arrays.toString(listener.getSslProtocols())
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

    void addRequestFilter(RequestFilterConfiguration config) throws ConfigurationNotValidException {
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

    NetworkListenerConfiguration getListener(NetworkListenerConfiguration.HostPort hostPort) {
        return listeners
                .stream()
                .filter(s -> s.getHost().equalsIgnoreCase(hostPort.getHost()) && s.getPort() == hostPort.getPort())
                .findFirst()
                .orElse(null);
    }

}
