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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration of a single connection pool
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionPoolConfiguration {
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ENDPOINT = 10;
    public static final int DEFAULT_IDLE_TIMEOUT = 60_000;
    public static final int DEFAULT_BORROW_TIMEOUT = 60_000;
    public static final int DEFAULT_MAX_LIFETIME = 100_000;
    public static final int DEFAULT_STUCK_REQUEST_TIMEOUT = 120_000;
    public static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    public static final int DEFAULT_DISPOSE_TIMEOUT = 300_000;
    public static final int DEFAULT_KEEPALIVE_IDLE = 300;
    public static final int DEFAULT_KEEPALIVE_INTERVAL = 60;
    public static final int DEFAULT_KEEPALIVE_COUNT = 8;
    public static final boolean DEFAULT_KEEPALIVE = true;

    private String id;
    private String domain;
    private int maxConnectionsPerEndpoint = DEFAULT_MAX_CONNECTIONS_PER_ENDPOINT;
    private int borrowTimeout = DEFAULT_BORROW_TIMEOUT;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int stuckRequestTimeout = DEFAULT_STUCK_REQUEST_TIMEOUT;
    private int idleTimeout = DEFAULT_IDLE_TIMEOUT;
    private int maxLifeTime = DEFAULT_MAX_LIFETIME;
    private int disposeTimeout = DEFAULT_DISPOSE_TIMEOUT;
    private int keepaliveIdle = DEFAULT_KEEPALIVE_IDLE;
    private int keepaliveInterval = DEFAULT_KEEPALIVE_INTERVAL;
    private int keepaliveCount = DEFAULT_KEEPALIVE_COUNT;
    private boolean keepAlive = DEFAULT_KEEPALIVE;
    private boolean enabled;
}
