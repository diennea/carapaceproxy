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

import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;

/**
 * Configuration for a TLS Certificate
 *
 * @author enrico.olivelli
 */
public class SSLCertificateConfiguration {

    public static enum CertificateMode {
        STATIC, ACME, MANUAL
    }

    private final String id;
    private final String hostname;
    private final String file;
    private final String password;
    private final boolean wildcard;
    private final CertificateMode mode;

    public SSLCertificateConfiguration(String hostname, String file, String password, CertificateMode mode) {
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
        this.file = file;
        this.password = password;
        this.mode = mode;
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

    public String getFile() {
        return file;
    }

    public String getPassword() {
        return password;
    }

    public boolean isDynamic() {
        return !STATIC.equals(mode);
    }

    public CertificateMode getMode() {
        return mode;
    }

    public boolean isMoreSpecific(SSLCertificateConfiguration other) {
        return hostname.length() > other.getHostname().length();
    }

    @Override
    public String toString() {
        return "SSLCertificateConfiguration{" + "id=" + id + ", hostname=" + hostname + ", mode=" + mode + '}';
    }

}
