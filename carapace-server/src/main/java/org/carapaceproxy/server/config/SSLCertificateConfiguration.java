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

import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.ACME;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.carapaceproxy.utils.CertificatesUtils;

/**
 * Configuration for a TLS Certificate
 *
 * @author enrico.olivelli
 */
@Data
public class SSLCertificateConfiguration {

    public static enum CertificateMode {
        STATIC, ACME, MANUAL
    }

    private final String id; // hostname or *.hostname or *
    private final String hostname;
    private final Set<String> subjectAlternativeNames;
    private final String file;
    private final String password;
    private final boolean wildcard;
    private final CertificateMode mode;
    private int daysBeforeRenewal;

    public SSLCertificateConfiguration(String hostname,
                                       Set<String> subjectAlternativeNames,
                                       String file,
                                       String password,
                                       CertificateMode mode) {
        this.id = hostname;
        if (hostname.equals("*")) {
            this.hostname = "";
            this.wildcard = true;
        } else if (CertificatesUtils.isWildcard(hostname)) {
            this.hostname = hostname.substring(2);
            this.wildcard = true;
        } else {
            this.hostname = hostname;
            this.wildcard = false;
        }
        this.subjectAlternativeNames = subjectAlternativeNames;
        this.file = file;
        this.password = password;
        this.mode = mode;
    }

    public boolean isDynamic() {
        return !STATIC.equals(mode);
    }

    public boolean isAcme() {
        return ACME.equals(mode);
    }

    public boolean isMoreSpecific(SSLCertificateConfiguration other) {
        return hostname.length() > other.getHostname().length();
    }

}
