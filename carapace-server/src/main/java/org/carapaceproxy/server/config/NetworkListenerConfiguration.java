/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server.config;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Listens for connections on the network
 */
public class NetworkListenerConfiguration {

    public static final List<String> DEFAULT_SSL_PROTOCOLS = Collections.unmodifiableList(Arrays.asList("TLSv1.2", "TLSv1.3"));

    private final String host;
    private final int port;
    private final boolean ssl;
    private final boolean ocsp;
    private final String sslCiphers;
    private final String defaultCertificate;
    private final String sslTrustoreFile;
    private final String sslTrustorePassword;
    private String[] sslProtocols = new String[0];
    private KeyStore trustStore;

    public HostPort getKey() {
        return new HostPort(host, port);
    }

    public static final class HostPort {

        private final String host;
        private final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 43 * hash + Objects.hashCode(this.host);
            hash = 43 * hash + this.port;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final HostPort other = (HostPort) obj;
            if (this.port != other.port) {
                return false;
            }
            if (!Objects.equals(this.host, other.host)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "HostPort{" + "host=" + host + ", port=" + port + '}';
        }

    }

    public NetworkListenerConfiguration(String host, int port) {
        this.host = host;
        this.port = port;
        this.ssl = false;
        this.sslCiphers = null;
        this.ocsp = false;
        this.defaultCertificate = null;
        this.sslTrustoreFile = null;
        this.sslTrustorePassword = null;
    }

    public NetworkListenerConfiguration(String host, int port, boolean ssl, boolean ocsp, String sslCiphers, String defaultCertificate, String sslTrustoreFile, String sslTrustorePassword) {
        this(
                host, port, ssl, ocsp, sslCiphers, defaultCertificate,
                sslTrustoreFile, sslTrustorePassword, DEFAULT_SSL_PROTOCOLS.toArray(new String[0])
        );
    }

    public NetworkListenerConfiguration(String host, int port, boolean ssl, boolean ocsp, String sslCiphers, String defaultCertificate, String sslTrustoreFile, String sslTrustorePassword,
                                        String... sslProtocols) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.ocsp = ocsp;
        this.sslCiphers = sslCiphers;
        this.defaultCertificate = defaultCertificate;
        this.sslTrustoreFile = sslTrustoreFile;
        this.sslTrustorePassword = sslTrustorePassword;
        if (ssl) {
            this.sslProtocols = sslProtocols;
        }
    }

    public String getSslTrustoreFile() {
        return sslTrustoreFile;
    }

    public String getSslTrustorePassword() {
        return sslTrustorePassword;
    }

    public String getDefaultCertificate() {
        return defaultCertificate;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isSsl() {
        return ssl;
    }

    public boolean isOcsp() {
        return ocsp;
    }

    public String getSslCiphers() {
        return sslCiphers;
    }

    public String[] getSslProtocols() {
        return sslProtocols;
    }

    public void setSslProtocols(String[] sslProtocols) {
        this.sslProtocols = sslProtocols;
    }

    public void loadTrustStore(File basePath) throws GeneralSecurityException, IOException {
        trustStore = loadKeyStoreFromFile(sslTrustoreFile, sslTrustorePassword, basePath);
    }

    public KeyStore getTrustStore() {
        return trustStore;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 89 * hash + Objects.hashCode(this.host);
        hash = 89 * hash + this.port;
        hash = 89 * hash + (this.ssl ? 1 : 0);
        hash = 89 * hash + (this.ocsp ? 1 : 0);
        hash = 89 * hash + Objects.hashCode(this.sslCiphers);
        hash = 89 * hash + Objects.hashCode(this.defaultCertificate);
        hash = 89 * hash + Objects.hashCode(this.sslTrustoreFile);
        hash = 89 * hash + Objects.hashCode(this.sslTrustorePassword);
        hash = 89 * hash + Arrays.deepHashCode(this.sslProtocols);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final NetworkListenerConfiguration other = (NetworkListenerConfiguration) obj;
        if (this.port != other.port) {
            return false;
        }
        if (this.ssl != other.ssl) {
            return false;
        }
        if (this.ocsp != other.ocsp) {
            return false;
        }
        if (!Objects.equals(this.host, other.host)) {
            return false;
        }
        if (!Objects.equals(this.sslCiphers, other.sslCiphers)) {
            return false;
        }
        if (!Objects.equals(this.defaultCertificate, other.defaultCertificate)) {
            return false;
        }
        if (!Objects.equals(this.sslTrustoreFile, other.sslTrustoreFile)) {
            return false;
        }
        if (!Objects.equals(this.sslTrustorePassword, other.sslTrustorePassword)) {
            return false;
        }
        if (!Arrays.deepEquals(this.sslProtocols, other.sslProtocols)) {
            return false;
        }
        return true;
    }

}
