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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.configstore.ConfigurationStore;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.RequestFilterConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import static nettyhttpproxy.server.filters.RequestFilterFactory.buildRequestFilter;

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

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {

        this.maxConnectionsPerEndpoint = Integer.parseInt(properties.getProperty("connectionsmanager.maxconnectionsperendpoint", maxConnectionsPerEndpoint + ""));
        this.idleTimeout = Integer.parseInt(properties.getProperty("connectionsmanager.idletimeout", idleTimeout + ""));
        this.stuckRequestTimeout = Integer.parseInt(properties.getProperty("connectionsmanager.stuckrequesttimeout", stuckRequestTimeout + ""));
        this.connectTimeout = Integer.parseInt(properties.getProperty("connectionsmanager.connecttimeout", connectTimeout + ""));
        LOG.info("connectionsmanager.maxconnectionsperendpoint=" + maxConnectionsPerEndpoint);
        LOG.info("connectionsmanager.idletimeout=" + idleTimeout);
        LOG.info("connectionsmanager.stuckrequesttimeout=" + stuckRequestTimeout);
        LOG.info("connectionsmanager.connecttimeout=" + connectTimeout);

        for (int i = 0; i < 100; i++) {
            tryConfigureCertificate(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureListener(i, properties);
        }
        for (int i = 0; i < 100; i++) {
            tryConfigureFilter(i, properties);
        }
    }

    private void tryConfigureCertificate(int i, ConfigurationStore properties) throws ConfigurationNotValidException {
        String prefix = "certificate." + i + ".";

        String certificateHostname = properties.getProperty(prefix + "hostname", "");

        if (!certificateHostname.isEmpty()) {
            String certificateFile = properties.getProperty(prefix + "sslcertfile", "");
            String certificatePassword = properties.getProperty(prefix + "sslcertfilepassword", "");
            LOG.log(Level.INFO, "Configuring SSL certificate {0}hostname={1}, file: {2}", new Object[]{prefix, certificateHostname, certificateFile});

            SSLCertificateConfiguration config
                    = new SSLCertificateConfiguration(certificateHostname, certificateFile, certificatePassword);
            this.addCertificate(config);

        }

    }

    private void tryConfigureListener(int i, ConfigurationStore properties) throws ConfigurationNotValidException {
        String prefix = "listener." + i + ".";
        String host = properties.getProperty(prefix + "host", "0.0.0.0");

        int port = Integer.parseInt(properties.getProperty(prefix + "port", "0"));

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

    private void tryConfigureFilter(int i,
            ConfigurationStore properties
    ) throws ConfigurationNotValidException {
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

}
