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
import java.util.Set;
import java.util.stream.Stream;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.carapaceproxy.api.response.FormValidationResponse;
import org.carapaceproxy.api.response.SimpleResponse;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.HttpProxyServer.ConnectionPoolStats;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.carapaceproxy.utils.StringUtils;

/**
 * Access to connection pools
 *
 * @author paolo.venturi
 */
@Path("/connectionpools")
@Produces("application/json")
public class ConnectionPoolsResource {

    @Context
    private ServletContext context;

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
        private int maxLifeTime;
        private int disposeTimeout;
        private int keepaliveIdle;
        private int keepaliveInterval;
        private int keepaliveCount;
        private boolean keepAlive;
        private boolean enabled;

        private int totalConnections;

        private static ConnectionPoolBean fromConfiguration(final ConnectionPoolConfiguration configuration, final int connections) {
            return new ConnectionPoolBean(
                    configuration.getId(),
                    configuration.getDomain(),
                    configuration.getMaxConnectionsPerEndpoint(),
                    configuration.getBorrowTimeout(),
                    configuration.getConnectTimeout(),
                    configuration.getStuckRequestTimeout(),
                    configuration.getIdleTimeout(),
                    configuration.getMaxLifeTime(),
                    configuration.getDisposeTimeout(),
                    configuration.getKeepaliveIdle(),
                    configuration.getKeepaliveInterval(),
                    configuration.getKeepaliveCount(),
                    configuration.isKeepAlive(),
                    configuration.isEnabled(),
                    connections
            );
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, ConnectionPoolBean> getAll() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        Map<String, ConnectionPoolBean> res = new HashMap<>();

        Collection<Map<String, ConnectionPoolStats>> stats = server.getConnectionPoolsStats().values();
        Map<String, Integer> poolsStats = new HashMap<>(stats.stream()
                .flatMap(m -> m.entrySet().stream())
                .collect(groupingBy(Map.Entry::getKey, summingInt(e -> e.getValue().getTotalConnections()))));

        // custom pools
        server.getCurrentConfiguration().getConnectionPools().forEach(conf -> {
            ConnectionPoolBean bean = fromConfiguration(conf, poolsStats);

            res.put(conf.getId(), bean);
        });

        // default pool
        ConnectionPoolConfiguration defaultConnectionPool = server.getCurrentConfiguration().getDefaultConnectionPool();
        res.put(defaultConnectionPool.getId(), ConnectionPoolBean.fromConfiguration(defaultConnectionPool, poolsStats.getOrDefault(defaultConnectionPool.getId(), 0)));

        return res;
    }

    @GET
    @Path("/{poolId}")
    @Produces(MediaType.APPLICATION_JSON)
    public ConnectionPoolBean getConnectionPool(final @PathParam("poolId") String poolId) {
        final var server = (HttpProxyServer) context.getAttribute("server");
        final var connectionPoolsStats = server.getConnectionPoolsStats();
        final var poolsStats = connectionPoolsStats.values().stream()
                .map(Map::entrySet)
                .flatMap(Set::stream)
                .collect(groupingBy(Map.Entry::getKey, summingInt(e -> e.getValue().getTotalConnections())));
        return Stream.concat(
                        server.getCurrentConfiguration().getConnectionPools().stream(),
                        Stream.of(server.getCurrentConfiguration().getDefaultConnectionPool())
                )
                .filter(configuration -> configuration.getId().equals(poolId))
                .map(configuration -> fromConfiguration(configuration, poolsStats))
                .findAny()
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createConnectionPool(final ConnectionPoolBean connectionPool) {
        final var server = (HttpProxyServer) context.getAttribute("server");
        final var configuration = new ConnectionPoolConfiguration(
                connectionPool.id,
                connectionPool.domain,
                connectionPool.maxConnectionsPerEndpoint,
                connectionPool.borrowTimeout,
                connectionPool.connectTimeout,
                connectionPool.stuckRequestTimeout,
                connectionPool.idleTimeout,
                connectionPool.maxLifeTime,
                connectionPool.disposeTimeout,
                connectionPool.keepaliveIdle,
                connectionPool.keepaliveInterval,
                connectionPool.keepaliveCount,
                connectionPool.keepAlive,
                connectionPool.enabled
        );
        if (StringUtils.isBlank(configuration.getId())) {
            return FormValidationResponse.fieldRequired("id");
        }
        if (StringUtils.isBlank(configuration.getDomain())) {
            return FormValidationResponse.fieldRequired("domain");
        }
        try {
            final String currentConfiguration = server.getDynamicConfigurationStore().toStringConfiguration();
            final PropertiesConfigurationStore configurationStore = ConfigResource.buildStore(currentConfiguration);
            configurationStore.saveConnectionPool(configuration);
            server.applyDynamicConfigurationFromAPI(configurationStore);
            return SimpleResponse.created();
        } catch (ConfigurationChangeInProgressException | InterruptedException | ConfigurationNotValidException e) {
            return SimpleResponse.error(e);
        }
    }

    private static ConnectionPoolBean fromConfiguration(final ConnectionPoolConfiguration configuration, final Map<String, Integer> poolsStats) {
        return ConnectionPoolBean.fromConfiguration(configuration, poolsStats.getOrDefault(configuration.getId(), 0));
    }

}
