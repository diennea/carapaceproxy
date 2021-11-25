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
package org.carapaceproxy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.ToString;
import org.carapaceproxy.client.EndpointKey;
import reactor.netty.resources.ConnectionPoolMetrics;

/**
 * Stats about an endpoint
 *
 * @author enrico.olivelli
 */
@Getter
@ToString
public class EndpointStats {

    private final EndpointKey key;
    private ConnectionPoolMetrics connectionPoolMetrics;
    private final AtomicInteger totalRequests = new AtomicInteger();
    private final AtomicLong lastActivity = new AtomicLong();

    public EndpointStats(EndpointKey key) {
        this.key = key;
    }

    public int getOpenConnections() {
        return connectionPoolMetrics.acquiredSize();
    }

    public void setConnectionPoolMetrics(ConnectionPoolMetrics connectionPoolMetrics) {
        this.connectionPoolMetrics = connectionPoolMetrics;
    }

}
