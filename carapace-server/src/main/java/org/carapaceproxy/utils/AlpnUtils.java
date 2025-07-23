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
package org.carapaceproxy.utils;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContextBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.carapaceproxy.core.EndpointKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.HttpProtocol;

/**
 * Utility class for ALPN (Application-Layer Protocol Negotiation) configuration.
 */
public class AlpnUtils {

    private static final Logger LOG = LoggerFactory.getLogger(AlpnUtils.class);

    /**
     * Configures ALPN for HTTP/2 support on a server.
     * This method checks if HTTP/2 is enabled in the protocols set and configures ALPN accordingly.
     *
     * @param sslContextBuilder the SslContextBuilder to configure
     * @param protocols the set of supported HTTP protocols
     * @param host the host name for logging
     * @param port the port number for logging
     * @return the configured SslContextBuilder
     */
    public static SslContextBuilder configureAlpnForServer(
            final SslContextBuilder sslContextBuilder,
            final Set<HttpProtocol> protocols,
            final String host,
            final int port
    ) {
        if (protocols.contains(HttpProtocol.H2)) {
            LOG.debug("Configuring ALPN for HTTP/2 support on listener {}:{}", host, port);
            List<String> alpnProtocols = new ArrayList<>();
            if (protocols.contains(HttpProtocol.H2)) {
                alpnProtocols.add(ApplicationProtocolNames.HTTP_2);
            }
            if (protocols.contains(HttpProtocol.HTTP11)) {
                alpnProtocols.add(ApplicationProtocolNames.HTTP_1_1);
            }

            sslContextBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    alpnProtocols
            ));
        }
        return sslContextBuilder;
    }

    /**
     * Configures ALPN for HTTP/2 support on a client.
     * This method checks if the protocol is HTTP/2 and configures ALPN accordingly.
     *
     * @param sslContextBuilder the SslContextBuilder to configure
     * @param protocol          the HTTP protocol to use
     * @param endpoint          the host name and port number for logging
     * @return the configured SslContextBuilder
     */
    public static SslContextBuilder configureAlpnForClient(final EndpointKey endpoint, final HttpProtocol protocol, final SslContextBuilder sslContextBuilder) {
        if (protocol == HttpProtocol.H2) {
            LOG.debug("Configuring ALPN for HTTP/2 support on backend connection to {}:{}", endpoint.host(), endpoint.port());
            sslContextBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1
            ));
        }
        return sslContextBuilder;
    }
}
