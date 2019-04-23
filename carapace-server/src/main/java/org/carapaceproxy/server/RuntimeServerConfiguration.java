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
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.configstore.ConfigurationStore;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.getClassname;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.getInt;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.getLong;
import static org.carapaceproxy.server.certiticates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.filters.RequestFilterFactory.buildRequestFilter;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.user.SimpleUserRealm;

/**
 * Configuration
 *
 * @author enrico.olivelli
 */
public class RuntimeServerConfiguration {

    private static final Logger LOG = Logger.getLogger(RuntimeServerConfiguration.class.getName());

    private final List<NetworkListenerConfiguration> listeners = new ArrayList<>();
    private final Map<String, SSLCertificateConfiguration> certificates = new HashMap<>();
    private final List<RequestFilterConfiguration> requestFilters = new ArrayList<>();
    private int maxConnectionsPerEndpoint = 10;
    private int idleTimeout = 60000;
    private int stuckRequestTimeout = 120000;
    private int connectTimeout = 10000;
    private long cacheMaxSize = 0;
    private long cacheMaxFileSize = 0;
    private String mapperClassname;
    private String accessLogPath = "access.log";
    private String accessLogTimestampFormat = "yyyy-MM-dd HH:mm:ss.SSS";
    private String accessLogFormat
            = "[<timestamp>] [<method> <host> <uri>] [uid:<user_id>, sid:<session_id>, ip:<client_ip>] "
            + "server=<server_ip>, act=<action_id>, route=<route_id>, backend=<backend_id>. "
            + "time t=<total_time>ms b=<backend_time>ms";
    private int accessLogMaxQueueCapacity = 2000;
    private int accessLogFlushInterval = 5000;
    private int accessLogWaitBetweenFailures = 10000;
    private String userRealmClassname;
    private int healthProbePeriod = 0;
    private int dynamicCertificatesManagerPeriod = 0;
    private int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;

    public String getAccessLogPath() {
        return accessLogPath;
    }

    public void setAccessLogPath(String accessLogPath) {
        this.accessLogPath = accessLogPath;
    }

    public String getAccessLogTimestampFormat() {
        return accessLogTimestampFormat;
    }

    public void setAccessLogTimestampFormat(String accessLogTimestampFormat) {
        this.accessLogTimestampFormat = accessLogTimestampFormat;
    }

    public String getAccessLogFormat() {
        return accessLogFormat;
    }

    public void setAccessLogFormat(String accessLogFormat) {
        this.accessLogFormat = accessLogFormat;
    }

    public int getAccessLogMaxQueueCapacity() {
        return accessLogMaxQueueCapacity;
    }

    public void setAccessLogMaxQueueCapacity(int accessLogMaxQueueCapacity) {
        this.accessLogMaxQueueCapacity = accessLogMaxQueueCapacity;
    }

    public int getAccessLogFlushInterval() {
        return accessLogFlushInterval;
    }

    public void setAccessLogFlushInterval(int accessLogFlushInterval) {
        this.accessLogFlushInterval = accessLogFlushInterval;
    }

    public int getAccessLogWaitBetweenFailures() {
        return accessLogWaitBetweenFailures;
    }

    public void setAccessLogWaitBetweenFailures(int accessLogWaitBetweenFailures) {
        this.accessLogWaitBetweenFailures = accessLogWaitBetweenFailures;
    }

    public String getMapperClassname() {
        return mapperClassname;
    }

    public void setMapperClassname(String mapperClassname) {
        this.mapperClassname = mapperClassname;
    }

    public String getUserRealmClassname() {
        return userRealmClassname;
    }

    public void setUserRealmClassname(String userRealmClassname) {
        this.userRealmClassname = userRealmClassname;
    }

    public int getMaxConnectionsPerEndpoint() {
        return maxConnectionsPerEndpoint;
    }

    public void setMaxConnectionsPerEndpoint(int maxConnectionsPerEndpoint) {
        this.maxConnectionsPerEndpoint = maxConnectionsPerEndpoint;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(int idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public int getStuckRequestTimeout() {
        return stuckRequestTimeout;
    }

    public void setStuckRequestTimeout(int stuckRequestTimeout) {
        this.stuckRequestTimeout = stuckRequestTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public long getCacheMaxSize() {
        return cacheMaxSize;
    }

    public void setCacheMaxSize(long cacheMaxSize) {
        this.cacheMaxSize = cacheMaxSize;
    }

    public long getCacheMaxFileSize() {
        return cacheMaxFileSize;
    }

    public void setCacheMaxFileSize(long cacheMaxFileSize) {
        this.cacheMaxFileSize = cacheMaxFileSize;
    }

    public int getHealthProbePeriod() {
        return healthProbePeriod;
    }

    @VisibleForTesting
    public void setHealthProbePeriod(int healthProbePeriod) {
        this.healthProbePeriod = healthProbePeriod;
    }

    public int getDynamicCertificatesManagerPeriod() {
        return dynamicCertificatesManagerPeriod;
    }

    @VisibleForTesting
    public void setDynamicCertificatesManagerPeriod(int dynamicCertificatesManagerPeriod) {
        this.dynamicCertificatesManagerPeriod = dynamicCertificatesManagerPeriod;
    }

    public int getKeyPairsSize() {
        return keyPairsSize;
    }

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        LOG.log(Level.INFO, "configuring from " + properties);
        this.maxConnectionsPerEndpoint = getInt("connectionsmanager.maxconnectionsperendpoint", maxConnectionsPerEndpoint, properties);
        this.idleTimeout = getInt("connectionsmanager.idletimeout", idleTimeout, properties);
        if (this.idleTimeout <= 0) {
            throw new ConfigurationNotValidException("Invalid value '" + this.idleTimeout + "' for connectionsmanager.idletimeout");
        }
        this.stuckRequestTimeout = getInt("connectionsmanager.stuckrequesttimeout", stuckRequestTimeout, properties);
        this.connectTimeout = getInt("connectionsmanager.connecttimeout", connectTimeout, properties);
        LOG.info("connectionsmanager.maxconnectionsperendpoint=" + maxConnectionsPerEndpoint);
        LOG.info("connectionsmanager.idletimeout=" + idleTimeout);
        LOG.info("connectionsmanager.stuckrequesttimeout=" + stuckRequestTimeout);
        LOG.info("connectionsmanager.connecttimeout=" + connectTimeout);

        this.mapperClassname = getClassname("mapper.class", StandardEndpointMapper.class.getName(), properties);
        LOG.log(Level.INFO, "mapper.class={0}", this.mapperClassname);

        this.cacheMaxSize = getLong("cache.maxsize", cacheMaxSize, properties);
        this.cacheMaxFileSize = getLong("cache.maxfilesize", cacheMaxFileSize, properties);
        LOG.info("cache.maxsize=" + cacheMaxSize);
        LOG.info("cache.maxfilesize=" + cacheMaxFileSize);

        this.accessLogPath = properties.getProperty("accesslog.path", accessLogPath);
        this.accessLogTimestampFormat = properties.getProperty("accesslog.format.timestamp", accessLogTimestampFormat);
        this.accessLogFormat = properties.getProperty("accesslog.format", accessLogFormat);
        this.accessLogMaxQueueCapacity = getInt("accesslog.queue.maxcapacity", accessLogMaxQueueCapacity, properties);
        this.accessLogFlushInterval = getInt("accesslog.flush.interval", accessLogFlushInterval, properties);
        this.accessLogWaitBetweenFailures = getInt("accesslog.failure.wait", accessLogWaitBetweenFailures, properties);
        String tsFormatExample;
        try {
            SimpleDateFormat formatter = new SimpleDateFormat(this.accessLogTimestampFormat);
            tsFormatExample = formatter.format(new Timestamp(System.currentTimeMillis()));
        } catch (Exception err) {
            throw new ConfigurationNotValidException("Invalid accesslog.format.timestamp='" + accessLogTimestampFormat + ": " + err);
        }
        LOG.info("accesslog.path=" + accessLogPath);
        LOG.info("accesslog.format.timestamp=" + accessLogTimestampFormat + " (example: " + tsFormatExample + ")");
        LOG.info("accesslog.format=" + accessLogFormat);
        LOG.info("accesslog.queue.maxcapacity=" + accessLogMaxQueueCapacity);
        LOG.info("accesslog.flush.interval=" + accessLogFlushInterval);
        LOG.info("accesslog.failure.wait=" + accessLogWaitBetweenFailures);

        this.userRealmClassname = getClassname("userrealm.class", SimpleUserRealm.class.getName(), properties);
        LOG.log(Level.INFO, "userrealm.class={0}", this.userRealmClassname);

        for (int i = 0; i < 100; i++) {
            tryConfigureCertificate(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureListener(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureFilter(i, properties);
        }

        healthProbePeriod = getInt("healthmanager.period", 0, properties);
        LOG.info("healthmanager.period=" + healthProbePeriod);

        dynamicCertificatesManagerPeriod = getInt("dynamiccertificatesmanager.period", 0, properties);
        LOG.info("dynamiccertificatesmanager.period=" + dynamicCertificatesManagerPeriod);
        keyPairsSize = getInt("dynamiccertificatesmanager.keypairssize", DEFAULT_KEYPAIRS_SIZE, properties);
        LOG.info("dynamiccertificatesmanager.keypairssize=" + keyPairsSize);
    }

    private void tryConfigureCertificate(int i, ConfigurationStore properties) throws ConfigurationNotValidException {
        String prefix = "certificate." + i + ".";

        String certificateHostname = properties.getProperty(prefix + "hostname", "");

        if (!certificateHostname.isEmpty()) {
            String certificateFile = properties.getProperty(prefix + "sslcertfile", "");
            String certificatePassword = properties.getProperty(prefix + "sslcertfilepassword", "");
            boolean isDynamic = properties.getProperty(prefix + "dynamic", "false").equalsIgnoreCase("true");
            LOG.log(Level.INFO, "Configuring SSL certificate {0}hostname={1}, file: {2}", new Object[]{prefix, certificateHostname, certificateFile});

            SSLCertificateConfiguration config = new SSLCertificateConfiguration(certificateHostname, certificateFile, certificatePassword, isDynamic);
            this.addCertificate(config);

        }
    }

    private void tryConfigureListener(int i, ConfigurationStore properties) throws ConfigurationNotValidException {
        String prefix = "listener." + i + ".";
        String host = properties.getProperty(prefix + "host", "0.0.0.0");

        int port = getInt(prefix + "port", 0, properties);

        if (port > 0) {
            boolean ssl = Boolean.parseBoolean(properties.getProperty(prefix + "ssl", "false"));
            boolean ocps = Boolean.parseBoolean(properties.getProperty(prefix + "ocps", "true"));
            String trustStoreFile = properties.getProperty(prefix + "ssltruststorefile", "");
            String trustStorePassword = properties.getProperty(prefix + "ssltruststorepassword", "");
            String sslciphers = properties.getProperty(prefix + "sslciphers", "");
            String defautlSslCertificate = properties.getProperty(prefix + "defaultcertificate", "*");
            NetworkListenerConfiguration config = new NetworkListenerConfiguration(host,
                    port, ssl, ocps, sslciphers, defautlSslCertificate,
                    trustStoreFile, trustStorePassword);
            this.addListener(config);

        }
    }

    private void tryConfigureFilter(int i, ConfigurationStore properties) throws ConfigurationNotValidException {
        String prefix = "filter." + i + ".";
        String type = properties.getProperty(prefix + "type", "").trim();

        if (type.isEmpty()) {
            return;
        }
        Map<String, String> filterConfig = new HashMap<>();
        RequestFilterConfiguration config = new RequestFilterConfiguration(type, filterConfig);
        LOG.log(Level.INFO, "configure filter " + prefix + "type={0}", type);
        properties.forEach(prefix, (k, v) -> {
            LOG.log(Level.INFO, prefix + k + "=" + v);
            filterConfig.put(k, v);
        });
        // try to build the filter for validation
        buildRequestFilter(config);
        this.addRequestFilter(config);
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
