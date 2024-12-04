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
package org.carapaceproxy.core;

import org.carapaceproxy.utils.StringUtils;

/**
 * Identifier of an endpoint
 *
 * @author enrico.olivelli
 */
public record EndpointKey(String host, int port) {

    /**
     * The minimum port value according to <a href="https://tools.ietf.org/html/rfc6335">RFC 6335</a>.
     */
    public static final int MIN_PORT = 0;
    /**
     * The maximum port value according to <a href="https://tools.ietf.org/html/rfc6335">RFC 6335</a>.
     */
    public static final int MAX_PORT = 0xffff;

    public EndpointKey {
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("Hostname cannot be blank");
        }
        if (port > MAX_PORT || port < MIN_PORT) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
    }

    public static EndpointKey make(String host, int port) {
        return new EndpointKey(host, port);
    }

    public static EndpointKey make(String hostAndPort) {
        int pos = hostAndPort.indexOf(':');
        if (pos <= 0) {
            return new EndpointKey(hostAndPort, MIN_PORT);
        }
        String host = hostAndPort.substring(0, pos);
        int port = Integer.parseInt(hostAndPort.substring(pos + 1));
        return new EndpointKey(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    public EndpointKey offsetPort(final int offsetPort) {
        return make(host(), port() + offsetPort);
    }
}
