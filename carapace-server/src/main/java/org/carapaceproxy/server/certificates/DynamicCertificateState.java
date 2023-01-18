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
package org.carapaceproxy.server.certificates;

/**
 *
 * @author paolo.venturi
 */
public enum DynamicCertificateState {
    /** Certificate waiting for issuing/renews */
    WAITING,

    /** Certificate domain reported as unreachable for issuing/renewing */
    DOMAIN_UNREACHABLE,

    /** For dns-challenge, wait for dns propagation */
    DNS_CHALLENGE_WAIT,

    /** Challenge verification by LE pending */
    VERIFYING,

    /** Challenge succeeded */
    VERIFIED,

    /** Certificate order pending */
    ORDERING,

    /** Challenge/order failed */
    REQUEST_FAILED,

    /** Certificate available (saved) and not expired */
    AVAILABLE,

    /** Certificate expired */
    EXPIRED;

    public String toStorableFormat() {
        return this.name();
    }

    public static DynamicCertificateState fromStorableFormat(String state) {
        return DynamicCertificateState.valueOf(state);
    }

    @Override
    public String toString() {
        return super.toString().toLowerCase().replaceAll("_", " ");
    }
}
