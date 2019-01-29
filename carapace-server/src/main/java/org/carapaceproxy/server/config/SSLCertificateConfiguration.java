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

/**
 * Configuration for a TLS Certificate
 *
 * @author enrico.olivelli
 */
public class SSLCertificateConfiguration {

    private final String id;
    private final String hostname;
    private final String sslCertificateFile;
    private final String sslCertificatePassword;
    private final boolean wildcard;
    private final boolean dynamic; // true for ACME certificates (p.e. issued by Let's Encrypt).    
    
    public SSLCertificateConfiguration(String hostname, String sslCertificateFile, String sslCertificatePassword, boolean dynamic) {
        this.id = hostname;
        if (hostname.equals("*")) {
            this.hostname = "";
            this.wildcard = true;
        } else if (hostname.startsWith("*.")) {
            this.hostname = hostname.substring(2);
            this.wildcard = true;
        } else {
            this.hostname = hostname;
            this.wildcard = false;
        }
        this.sslCertificateFile = sslCertificateFile;
        this.sslCertificatePassword = sslCertificatePassword;
        this.dynamic = dynamic;
    }

    public SSLCertificateConfiguration(String hostname, String sslCertificateFile, String sslCertificatePassword) {
        this(hostname, sslCertificateFile, sslCertificatePassword, false);
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public boolean isWildcard() {
        return wildcard;
    }

    public String getSslCertificateFile() {
        return sslCertificateFile;
    }

    public String getSslCertificatePassword() {
        return sslCertificatePassword;
    }

    public boolean isDynamic() {
        return this.dynamic;
    }

    @Override
    public String toString() {
        return "SSLCertificateConfiguration{" + "id=" + id + '}';
    }

}
