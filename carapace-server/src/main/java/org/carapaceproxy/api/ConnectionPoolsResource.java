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
package org.carapaceproxy.api;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.HttpProxyServer.ConnectionPoolStats;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;

/**
 * Access to connection pools
 *
 * @author paolo.venturi
 */
@Path("/connectionpools")
@Produces("application/json")
public class ConnectionPoolsResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class ConnectionPoolBean {

        private String id;
        private String domain;
        private int maxConnectionsPerEndpoint;
        private int borrowTimeout;
        private int connectTimeout;
        private int stuckRequestTimeout;
        private int idleTimeout;
        private int disposeTimeout;
        private int keepaliveIdle;
        private int keepaliveInterval;
        private int keepaliveCount;
        private boolean keepAlive;
        private boolean enabled;

        private int totalConnections;
    }

    @GET
    public Map<String, ConnectionPoolBean> getAll() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        Map<String, ConnectionPoolBean> res = new HashMap<>();

        Map<String, Integer> poolsStats = new HashMap<>();
        Collection<Map<String, ConnectionPoolStats>> stats = server.getConnectionPoolsStats().values();
        if (stats != null) {
            poolsStats.putAll(stats.stream()
                    .flatMap(m -> m.entrySet().stream())
                    .collect(groupingBy(Map.Entry::getKey, summingInt(e -> e.getValue().getTotalConnections())))
            );
        }

        // custom pools
        server.getCurrentConfiguration().getConnectionPools().forEach(conf -> {
            ConnectionPoolBean bean = new ConnectionPoolBean(conf.getId(),
                    conf.getDomain(),
                    conf.getMaxConnectionsPerEndpoint(),
                    conf.getBorrowTimeout(),
                    conf.getConnectTimeout(),
                    conf.getStuckRequestTimeout(),
                    conf.getIdleTimeout(),
                    conf.getDisposeTimeout(),
                    conf.getKeepaliveIdle(),
                    conf.getKeepaliveInterval(),
                    conf.getKeepaliveCount(),
                    conf.isKeepAlive(),
                    conf.isEnabled(),
                    poolsStats.getOrDefault(conf.getId(), 0)
            );

            res.put(conf.getId(), bean);
        });

        // default pool
        ConnectionPoolConfiguration defaultConnectionPool = server.getCurrentConfiguration().getDefaultConnectionPool();
        res.put(defaultConnectionPool.getId(), new ConnectionPoolBean(
                defaultConnectionPool.getId(),
                defaultConnectionPool.getDomain(),
                defaultConnectionPool.getMaxConnectionsPerEndpoint(),
                defaultConnectionPool.getBorrowTimeout(),
                defaultConnectionPool.getConnectTimeout(),
                defaultConnectionPool.getStuckRequestTimeout(),
                defaultConnectionPool.getIdleTimeout(),
                defaultConnectionPool.getDisposeTimeout(),
                defaultConnectionPool.getKeepaliveIdle(),
                defaultConnectionPool.getKeepaliveInterval(),
                defaultConnectionPool.getKeepaliveCount(),
                defaultConnectionPool.isKeepAlive(),
                defaultConnectionPool.isEnabled(),
                poolsStats.getOrDefault(defaultConnectionPool.getId(), 0)
        ));

        return res;
    }

}
