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

import java.util.Objects;

/**
 * Configuration of a single backend server
 */
public class BackendConfiguration {

    private final String id;
    private final String host;
    private final int port;
    private final String probePath;

    public BackendConfiguration(String id, String host, int port, String probePath) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.probePath = probePath;
    }

    public String getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getProbePath() {
        return probePath;
    }

    public String getHostPort() {
        return host + ":" + port;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 53 * hash + Objects.hashCode(this.id);
        hash = 53 * hash + Objects.hashCode(this.host);
        hash = 53 * hash + this.port;
        hash = 53 * hash + Objects.hashCode(this.probePath);
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
        final BackendConfiguration other = (BackendConfiguration) obj;
        if (this.port != other.port) {
            return false;
        }
        if (!Objects.equals(this.id, other.id)) {
            return false;
        }
        if (!Objects.equals(this.host, other.host)) {
            return false;
        }
        if (!Objects.equals(this.probePath, other.probePath)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "BackendConfiguration{" + "id=" + id + ", host=" + host + ", port=" + port + ", probePath=" + probePath + '}';
    }
}
