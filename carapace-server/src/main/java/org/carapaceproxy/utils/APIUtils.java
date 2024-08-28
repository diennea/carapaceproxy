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
package org.carapaceproxy.utils;

import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.DNS_CHALLENGE_WAIT;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.DOMAIN_UNREACHABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.EXPIRED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.ORDERING;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.VERIFIED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.VERIFYING;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.ACME;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;

/**
 * Utilities for the API
 *
 * @author paolo.venturi
 */
public abstract class APIUtils {

    private APIUtils() {
    }

    public static String certificateStateToString(DynamicCertificateState state) {
        return switch (state) {
            case null -> "unknown";
            case WAITING -> "waiting";
            case DOMAIN_UNREACHABLE -> "domain unreachable";
            case DNS_CHALLENGE_WAIT -> "dns-challenge-wait";
            case VERIFYING -> "verifying";
            case VERIFIED -> "verified";
            case ORDERING -> "ordering";
            case REQUEST_FAILED -> "request failed";
            case AVAILABLE -> "available";
            case EXPIRED -> "expired";
        };
    }

    public static DynamicCertificateState stringToCertificateState(String state) {
        if (state == null) {
            return null;
        }
        return switch (state.toLowerCase()) {
            case "waiting" -> WAITING;
            case "domain unreachable" -> DOMAIN_UNREACHABLE;
            case "dns-challenge-wait" -> DNS_CHALLENGE_WAIT;
            case "verifying" -> VERIFYING;
            case "verified" -> VERIFIED;
            case "ordering" -> ORDERING;
            case "request failed" -> REQUEST_FAILED;
            case "available" -> AVAILABLE;
            case "expired" -> EXPIRED;
            default -> null;
        };
    }

    public static String certificateModeToString(SSLCertificateConfiguration.CertificateMode mode) {
        return switch (mode) {
            case STATIC -> "static";
            case ACME -> "acme";
            case MANUAL -> "manual";
            case null -> "unknown";
        };
    }

    public static SSLCertificateConfiguration.CertificateMode stringToCertificateMode(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.toLowerCase()) {
            case "static" -> STATIC;
            case "acme" -> ACME;
            case "manual" -> MANUAL;
            default -> null;
        };
    }
}
