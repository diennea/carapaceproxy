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
package nettyhttpproxy.server.config;

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

    public String toBackendId() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return "BackendConfiguration{" + "id=" + id + ", host=" + host + ", port=" + port + ", probePath=" + probePath + '}';
    }
}
