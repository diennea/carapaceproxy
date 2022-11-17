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
package org.carapaceproxy.server.config;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import lombok.Data;

/**
 * Listens for connections on the network
 */
public class NetworkListenerConfiguration {

    public static final Set<String> DEFAULT_SSL_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String sslCiphers;
    private final String defaultCertificate;
    private String[] sslProtocols = new String[0];

    public HostPort getKey() {
        return new HostPort(host, port);
    }

    @Data
    public static final class HostPort {

        private final String host;
        private final int port;

        @Override
        public String toString() {
            return host + ":" + port;
        }

    }

    public NetworkListenerConfiguration(String host, int port) {
        this.host = host;
        this.port = port;
        this.ssl = false;
        this.sslCiphers = null;
        this.defaultCertificate = null;
    }

    public NetworkListenerConfiguration(String host, int port, boolean ssl, String sslCiphers, String defaultCertificate) {
        this(
                host, port, ssl, sslCiphers, defaultCertificate, DEFAULT_SSL_PROTOCOLS.toArray(new String[0])
        );
    }

    public NetworkListenerConfiguration(String host, int port, boolean ssl, String sslCiphers, String defaultCertificate,
                                        String... sslProtocols) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.sslCiphers = sslCiphers;
        this.defaultCertificate = defaultCertificate;
        if (ssl) {
            this.sslProtocols = sslProtocols;
        }
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

    public String getSslCiphers() {
        return sslCiphers;
    }

    public String[] getSslProtocols() {
        return sslProtocols;
    }

    public void setSslProtocols(String[] sslProtocols) {
        this.sslProtocols = sslProtocols;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 89 * hash + Objects.hashCode(this.host);
        hash = 89 * hash + this.port;
        hash = 89 * hash + (this.ssl ? 1 : 0);
        hash = 89 * hash + Objects.hashCode(this.sslCiphers);
        hash = 89 * hash + Objects.hashCode(this.defaultCertificate);
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
        if (!Objects.equals(this.host, other.host)) {
            return false;
        }
        if (!Objects.equals(this.sslCiphers, other.sslCiphers)) {
            return false;
        }
        if (!Objects.equals(this.defaultCertificate, other.defaultCertificate)) {
            return false;
        }
        if (!Arrays.deepEquals(this.sslProtocols, other.sslProtocols)) {
            return false;
        }
        return true;
    }

}
