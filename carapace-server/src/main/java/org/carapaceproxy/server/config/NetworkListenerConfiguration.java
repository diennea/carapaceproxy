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
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.ForwardedStrategies;
import reactor.netty.http.HttpProtocol;

/**
 * Listens for connections on the network
 */
public record NetworkListenerConfiguration(
        String host,
        int port,
        boolean ssl,
        String sslCiphers,
        String defaultCertificate,
        Set<String> sslProtocols,
        int soBacklog,
        boolean keepAlive,
        int keepAliveIdle,
        int keepAliveInterval,
        int keepAliveCount,
        int maxKeepAliveRequests,
        String forwardedStrategy,
        Set<String> trustedIps,
        Set<HttpProtocol> protocols,
        ChannelGroup group
) {

    public static final Set<String> DEFAULT_SSL_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");
    public static final int DEFAULT_SO_BACKLOG = 128;
    public static final boolean DEFAULT_KEEP_ALIVE = true;
    public static final int DEFAULT_KEEP_ALIVE_IDLE = 300;
    public static final int DEFAULT_KEEP_ALIVE_INTERVAL = 60;
    public static final int DEFAULT_KEEP_ALIVE_COUNT = 8;
    public static final int DEFAULT_MAX_KEEP_ALIVE_REQUESTS = 1000;
    public static final String DEFAULT_FORWARDED_STRATEGY = ForwardedStrategies.preserve().name();

    public NetworkListenerConfiguration {
        if (protocols == null || protocols.isEmpty()) {
            throw new IllegalArgumentException("At least one HTTP protocol is required!!!");
        }
        if (!ssl && protocols.contains(H2)) {
            throw new IllegalArgumentException("H2 requires SSL support");
        }
    }

    public static NetworkListenerConfiguration withDefault(final String host, final int port) {
        return withDefault(
                host,
                port,
                false,
                null,
                null,
                DEFAULT_SO_BACKLOG,
                DEFAULT_KEEP_ALIVE,
                DEFAULT_KEEP_ALIVE_IDLE,
                DEFAULT_KEEP_ALIVE_INTERVAL,
                DEFAULT_KEEP_ALIVE_COUNT,
                DEFAULT_MAX_KEEP_ALIVE_REQUESTS
        );
    }

    public static NetworkListenerConfiguration withDefault(
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
        return new NetworkListenerConfiguration(
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
                getDefaultHttpProtocols(ssl),
                new DefaultChannelGroup(new DefaultEventExecutor()));
    }

    public static Set<HttpProtocol> getDefaultHttpProtocols(final boolean ssl) {
        return Set.of(HTTP11, ssl ? H2 : H2C);
    }

    public EndpointKey getKey() {
        return new EndpointKey(host, port);
    }
}
