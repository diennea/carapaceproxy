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
package nettyhttpproxy.server.config;

/**
 * Listens for connections on the network
 */
public class NetworkListenerConfiguration {

    private final String host;
    private final int port;
    private final boolean ssl;
    private final boolean ocps;
    private final String sslCertificateFile;
    private final String sslCertificatePassword;

    private final String sslTrustoreFile;
    private final String sslTrustorePassword;

    private final String sslCiphers;

    public NetworkListenerConfiguration(String host, int port) {
        this.host = host;
        this.port = port;
        this.sslCertificateFile = null;
        this.sslCertificatePassword = null;
        this.sslTrustoreFile = null;
        this.sslTrustorePassword = null;
        this.ssl = false;
        this.sslCiphers = null;
        this.ocps = false;
    }

    public NetworkListenerConfiguration(String host, int port, boolean ssl,
            String sslCertificateFile,
            String sslCertificatePassword,
            String sslTrustoreFile,
            String sslTrustorePassword,
            String sslCiphers,
            boolean ocps) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.sslCertificateFile = sslCertificateFile;
        this.sslCertificatePassword = sslCertificatePassword;
        this.sslTrustoreFile = sslTrustoreFile;
        this.sslTrustorePassword = sslTrustorePassword;
        this.sslCiphers = sslCiphers;
        this.ocps = ocps;
    }

    public boolean isOcps() {
        return ocps;
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

    public String getSslCertificateFile() {
        return sslCertificateFile;
    }

    public String getSslCertificatePassword() {
        return sslCertificatePassword;
    }

    public String getSslCiphers() {
        return sslCiphers;
    }

    public String getSslTrustoreFile() {
        return sslTrustoreFile;
    }

    public String getSslTrustorePassword() {
        return sslTrustorePassword;
    }

}
