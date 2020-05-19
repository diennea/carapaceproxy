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

    /*
     * Utilities for CertificatesResource
     */
    public static String certificateStateToString(DynamicCertificateState state) {
        if (state == null) {
            return "unknown";
        }
        switch (state) {
            case WAITING:
                return "waiting"; // certificate waiting for issuing/renews
            case DNS_CHALLENGE_WAIT:
                return "dns-challenge-wait"; // dns challenge waiting to be visible before CA checking
            case VERIFYING:
                return "verifying"; // challenge verification by LE pending
            case VERIFIED:
                return "verified"; // challenge succeded
            case ORDERING:
                return "ordering"; // certificate order pending
            case REQUEST_FAILED:
                return "request failed"; // challenge/order failed
            case AVAILABLE:
                return "available";// certificate available(saved) and not expired
            case EXPIRED:     // certificate expired
                return "expired";
            default:
                return "unknown";
        }
    }

    public static DynamicCertificateState stringToCertificateState(String state) {
        if (state == null) {
            return null;
        }
        switch (state.toLowerCase()) {
            case "waiting":
                return WAITING;
            case "dns-challenge-wait":
                return DNS_CHALLENGE_WAIT;
            case "verifying":
                return VERIFYING;
            case "verified":
                return VERIFIED;
            case "ordering":
                return ORDERING;
            case "request failed":
                return REQUEST_FAILED;
            case "available":
                return AVAILABLE;
            case "expired":
                return EXPIRED;
            default:
                return null;
        }
    }

    public static String certificateModeToString(SSLCertificateConfiguration.CertificateMode mode) {
        if (mode == null) {
            return "unknown";
        }
        switch (mode) {
            case STATIC:
                return "static";
            case ACME:
                return "acme";
            case MANUAL:
                return "manual";
            default:
                return "unknown";
        }
    }

    public static SSLCertificateConfiguration.CertificateMode stringToCertificateMode(String mode) {
        if (mode == null) {
            return null;
        }
        switch (mode.toLowerCase()) {
            case "static":
                return STATIC;
            case "acme":
                return ACME;
            case "manual":
                return MANUAL;
            default:
                return null;
        }
    }
}
