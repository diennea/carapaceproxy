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
package org.carapaceproxy.client;

import java.util.Objects;

/**
 * Identifier of an endpoint
 *
 * @author enrico.olivelli
 */
public final class EndpointKey {

    private final String host;
    private final int port;
    private final String hostPort;

    public static EndpointKey make(String host, int port) {
        return new EndpointKey(host, port);
    }

    public static EndpointKey make(String hostAndPort) {
        int pos = hostAndPort.indexOf(':');
        if (pos > 0) {
            String host = hostAndPort.substring(0, pos);
            int port = Integer.parseInt(hostAndPort.substring(pos + 1, hostAndPort.length()));
            return new EndpointKey(host, port);
        } else {
            return new EndpointKey(hostAndPort, 0);
        }
    }

    public EndpointKey(String host, int port) {
        this.host = host;
        this.port = port;
        this.hostPort = host + ":" + port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 23 * hash + Objects.hashCode(this.host);
        hash = 23 * hash + this.port;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final EndpointKey other = (EndpointKey) obj;
        if (this.port != other.port) {
            return false;
        }
        if (!Objects.equals(this.host, other.host)) {
            return false;
        }
        return true;
    }

    public String getHostPort() {
        return hostPort;
    }

    @Override
    public String toString() {
        return "EndpointKey{" + "host=" + host + ", port=" + port + '}';
    }

}
