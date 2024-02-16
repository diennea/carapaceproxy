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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final Set<String> subjectAltNames;
    private final String file;
    private final String password;
    private final boolean wildcard;
    private final CertificateMode mode;
    private int daysBeforeRenewal;

    public SSLCertificateConfiguration(String hostname,
                                       Set<String> subjectAltNames,
                                       String file,
                                       String password,
                                       CertificateMode mode) {
        this.id = Objects.requireNonNull(hostname);
        if (hostname.equals("*")) {
            this.hostname = "";
            this.wildcard = true;
        } else if (CertificatesUtils.isWildcard(hostname)) {
            this.hostname = CertificatesUtils.removeWildcard(hostname).trim();
            this.wildcard = true;
        } else {
            this.hostname = hostname.trim();
            this.wildcard = false;
        }
        this.subjectAltNames = Optional
                .ofNullable(subjectAltNames)
                .stream().flatMap(Set::stream)
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        this.file = file;
        this.password = password;
        this.mode = mode;
    }

    public boolean isWildcard() {
        return wildcard || subjectAltNames != null && subjectAltNames.stream().anyMatch(CertificatesUtils::isWildcard);
    }

    public boolean isDynamic() {
        return !STATIC.equals(mode);
    }

    public boolean isAcme() {
        return ACME.equals(mode);
    }

    public boolean isMoreSpecific(SSLCertificateConfiguration other) {
        if (subjectAltNames == null || subjectAltNames.isEmpty()) {
            return hostname.length() > other.getHostname().length();
        }

        final int maxOtherNameLength = other.getNames().stream()
                .map(CertificatesUtils::removeWildcard)
                .map(String::length)
                .max(Integer::compareTo)
                .orElse(0);

        for (var n : getNames()) {
            final var name = CertificatesUtils.removeWildcard(n);
            if (name.length() > maxOtherNameLength) {
                return true;
            }
        }
        return false;
    }



    public Collection<String> getNames() {
        return new ArrayList<>() {{
            add(id); // hostname or *.hostname
            if (subjectAltNames != null) {
                addAll(subjectAltNames);
            }
        }};
    }
}
