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

import static reactor.netty.http.HttpProtocol.H2;
import static reactor.netty.http.HttpProtocol.H2C;
import static reactor.netty.http.HttpProtocol.HTTP11;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import org.carapaceproxy.core.ForwardedStrategies;
import reactor.netty.http.HttpProtocol;

/**
 * Listens for connections on the network
 */
@Getter
public class NetworkListenerConfiguration {

    public static final Set<String> DEFAULT_SSL_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");
    public static final int DEFAULT_SO_BACKLOG = 128;
    public static final int DEFAULT_KEEP_ALIVE_IDLE = 300;
    public static final int DEFAULT_KEEP_ALIVE_INTERVAL = 60;
    public static final int DEFAULT_KEEP_ALIVE_COUNT = 8;
    public static final int DEFAULT_MAX_KEEP_ALIVE_REQUESTS = 1000;
    public static final String DEFAULT_FORWARDED_STRATEGY = ForwardedStrategies.preserve().name();

    private final String host;
    private final int port;
    private final boolean ssl;
    private final String sslCiphers;
    private final String defaultCertificate;
    private final String forwardedStrategy;
    private final Set<String> trustedIps;
    private final Set<String> sslProtocols;
    private final int soBacklog;
    private final boolean keepAlive;
    private final int keepAliveIdle;
    private final int keepAliveInterval;
    private final int keepAliveCount;
    private final int maxKeepAliveRequests;
    private final ChannelGroup group;
    private final Set<HttpProtocol> protocols;

    public NetworkListenerConfiguration(final String host, final int port) {
        this(
                host,
                port,
                false,
                null,
                null,
                DEFAULT_SO_BACKLOG,
                true,
                DEFAULT_KEEP_ALIVE_IDLE,
                DEFAULT_KEEP_ALIVE_INTERVAL,
                DEFAULT_KEEP_ALIVE_COUNT,
                DEFAULT_MAX_KEEP_ALIVE_REQUESTS
        );
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
        this(
                host,
                port,
                ssl,
                sslCiphers,
                defaultCertificate,
                DEFAULT_SSL_PROTOCOLS,
                soBacklog,
                keepAlive,
                keepAliveIdle,
                keepAliveInterval,
                keepAliveCount,
                maxKeepAliveRequests,
                DEFAULT_FORWARDED_STRATEGY,
                Set.of(),
                getDefaultHttpProtocols(ssl)
        );
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
            final String forwardedStrategy,
            final Set<String> trustedIps,
            final Set<String> protocols) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.sslCiphers = sslCiphers;
        this.defaultCertificate = defaultCertificate;
        this.forwardedStrategy = forwardedStrategy;
        this.trustedIps = trustedIps;
        this.sslProtocols = ssl && sslProtocols != null ? Set.copyOf(sslProtocols) : Set.of();
        this.soBacklog = soBacklog;
        this.keepAlive = keepAlive;
        this.keepAliveIdle = keepAliveIdle;
        this.keepAliveInterval = keepAliveInterval;
        this.keepAliveCount = keepAliveCount;
        this.maxKeepAliveRequests = maxKeepAliveRequests;
        this.group = new DefaultChannelGroup(new DefaultEventExecutor());
        if (protocols == null || protocols.isEmpty()) {
            throw new IllegalArgumentException("At least one HTTP protocol is required!!!");
        }
        final var httpProtocols = protocols.stream()
                .map(String::toUpperCase)
                .map(HttpProtocol::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        if (!ssl && httpProtocols.contains(H2)) {
            throw new IllegalArgumentException("H2 requires SSL support");
        }
        this.protocols = httpProtocols;
    }

    public HostPort getKey() {
        return new HostPort(host, port);
    }

    public Set<HttpProtocol> getProtocols() {
        return protocols;
    }

    public static Set<String> getDefaultHttpProtocols(final boolean ssl) {
        // return Set.of(HTTP11.name(), (ssl ? H2 : H2C).name());
        if (ssl) {
            // todo: until #410 is done, we will allow HTTP/2 only in clear-text
            return Set.of(HTTP11.name());
        }
        return Set.of(HTTP11.name(), H2C.name());
    }
}
