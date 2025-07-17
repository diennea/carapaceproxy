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

import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.server.backends.BackendHealthStatus.Status;

/**
 * Configuration of a single backend server.
 *
 * @param id                   an arbitrary ID of the backend
 * @param hostPort             the host:port tuple for the backend
 * @param probePath            a path to use to probe the backend for reachability
 * @param safeCapacity         a capacity that is considered safe even when {@link Status#COLD cold}
 * @param ssl                  whether to use SSL when connecting to this backend
 * @param caCertificatePath    path to a CA certificate to trust when connecting to this backend (optional)
 * @param caCertificatePassword password for the CA certificate (optional)
 */
public record BackendConfiguration(String id, EndpointKey hostPort, String probePath, int safeCapacity, boolean ssl, String caCertificatePath, String caCertificatePassword) {

    /**
     * Configuration of a single backend server.
     *
     * @param id                   an arbitrary ID of the backend
     * @param host                 the host name
     * @param port                 the port to use
     * @param probePath            a path to use to probe the backend for reachability
     * @param safeCapacity         a capacity that is considered safe even when {@link Status#COLD cold}, or 0 for an infinite capacity
     * @param ssl                  whether to use SSL when connecting to this backend
     * @param caCertificatePath    path to a CA certificate to trust when connecting to this backend (optional)
     * @param caCertificatePassword password for the CA certificate (optional)
     */
    public BackendConfiguration(final String id, final String host, final int port, final String probePath, final int safeCapacity, final boolean ssl, final String caCertificatePath, final String caCertificatePassword) {
        this(id, new EndpointKey(host, port), probePath, safeCapacity, ssl, caCertificatePath, caCertificatePassword);
    }

    /**
     * Configuration of a single backend server.
     *
     * @param id                   an arbitrary ID of the backend
     * @param host                 the host name
     * @param port                 the port to use
     * @param probePath            a path to use to probe the backend for reachability
     * @param safeCapacity         a capacity that is considered safe even when {@link Status#COLD cold}, or 0 for an infinite capacity
     * @param ssl                  whether to use SSL when connecting to this backend
     * @param caCertificatePath    path to a CA certificate to trust when connecting to this backend (optional)
     */
    public BackendConfiguration(final String id, final String host, final int port, final String probePath, final int safeCapacity, final boolean ssl, final String caCertificatePath) {
        this(id, host, port, probePath, safeCapacity, ssl, caCertificatePath, null);
    }

    /**
     * Configuration of a single backend server.
     *
     * @param id           an arbitrary ID of the backend
     * @param host         the host name
     * @param port         the port to use
     * @param probePath    a path to use to probe the backend for reachability
     * @param safeCapacity a capacity that is considered safe even when {@link Status#COLD cold}, or 0 for an infinite capacity
     * @param ssl          whether to use SSL when connecting to this backend
     */
    public BackendConfiguration(final String id, final String host, final int port, final String probePath, final int safeCapacity, final boolean ssl) {
        this(id, host, port, probePath, safeCapacity, ssl, null);
    }

    /**
     * Configuration of a single backend server.
     *
     * @param id           an arbitrary ID of the backend
     * @param host         the host name
     * @param port         the port to use
     * @param probePath    a path to use to probe the backend for reachability
     * @param safeCapacity a capacity that is considered safe even when {@link Status#COLD cold}, or 0 for an infinite capacity
     */
    public BackendConfiguration(final String id, final String host, final int port, final String probePath, final int safeCapacity) {
        this(id, host, port, probePath, safeCapacity, false, null, null);
    }

    public String host() {
        return hostPort.host();
    }

    public int port() {
        return hostPort.port();
    }
}
