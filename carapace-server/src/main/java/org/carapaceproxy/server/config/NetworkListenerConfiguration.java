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

import java.util.Set;
import lombok.Data;
import reactor.netty.http.HttpProtocol;

/**
 * Listens for connections on the network
 */
@Data
public class NetworkListenerConfiguration {

    public static final Set<String> DEFAULT_SSL_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");
    public static final String DEFAULT_HTTP_PROTOCOL = HttpProtocol.HTTP11.name();

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String sslCiphers;
    private final String defaultCertificate;
    private final Set<String> sslProtocols;
    private final int soBacklog;
    private final boolean keepAlive;
    private final int keepAliveIdle;
    private final int keepAliveInterval;
    private final int keepAliveCount;
    private final int maxKeepAliveRequests;
    private final HttpProtocol httpProtocol;

    public NetworkListenerConfiguration(final String host, final int port) {
        this(host, port, false, null, null, Set.of(),
                128, true, 300, 60, 8, 1000, DEFAULT_HTTP_PROTOCOL);
    }

    public NetworkListenerConfiguration(
            final String host,
            final int port,
            final boolean ssl,
            final String sslCiphers,
            final String defaultCertificate,
            final int soBacklog,
            final boolean keepAlive,
            final int keepAliveIdle,
            final int keepAliveInterval,
            final int keepAliveCount,
            final int maxKeepAliveRequests) {
        this(host, port, ssl, sslCiphers, defaultCertificate, DEFAULT_SSL_PROTOCOLS,
                soBacklog, keepAlive, keepAliveIdle, keepAliveInterval, keepAliveCount, maxKeepAliveRequests, DEFAULT_HTTP_PROTOCOL);
    }

    public NetworkListenerConfiguration(
            final String host,
            final int port,
            final boolean ssl,
            final String sslCiphers,
            final String defaultCertificate,
            final Set<String> sslProtocols,
            final int soBacklog,
            final boolean keepAlive,
            final int keepAliveIdle,
            final int keepAliveInterval,
            final int keepAliveCount,
            final int maxKeepAliveRequests,
            final String httpProtocol
    ) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.sslCiphers = sslCiphers;
        this.defaultCertificate = defaultCertificate;
        this.sslProtocols = ssl && sslProtocols != null ? Set.copyOf(sslProtocols) : Set.of();
        this.soBacklog = soBacklog;
        this.keepAlive = keepAlive;
        this.keepAliveIdle = keepAliveIdle;
        this.keepAliveInterval = keepAliveInterval;
        this.keepAliveCount = keepAliveCount;
        this.maxKeepAliveRequests = maxKeepAliveRequests;
        if (httpProtocol == null) {
            throw new IllegalArgumentException("At least one HTTP protocol is required!!!");
        }
        if (!ssl && "h2".equalsIgnoreCase(httpProtocol)) {
            throw new IllegalArgumentException("H2 requires SSL support");
        }
        this.httpProtocol = HttpProtocol.valueOf(httpProtocol.toUpperCase());
    }

    public HostPort getKey() {
        return new HostPort(host, port);
    }

    public record HostPort(String host, int port) {
    }
}
