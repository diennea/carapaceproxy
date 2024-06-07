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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;

public class TestEndpointMapper extends EndpointMapper {

    private final String host;
    private final int port;
    private final boolean cacheAll;
    private final Map<String, BackendConfiguration> backends;
    private final List<RouteConfiguration> routes = new ArrayList<>();
    private final List<ActionConfiguration> actions = new ArrayList<>();
    private final List<DirectorConfiguration> directors = new ArrayList<>();
    private final List<CustomHeader> headers = new ArrayList<>();

    public TestEndpointMapper(String host, int port) {
        this(host, port, false);
    }

    public TestEndpointMapper(String host, int port, boolean cacheAll) {
        this(host, port, cacheAll, Map.of(host, new BackendConfiguration(host, host, port, "/index.html")));
    }

    public TestEndpointMapper(String host, int port, boolean cacheAll, Map<String, BackendConfiguration> backends) {
        this.host = host;
        this.port = port;
        this.cacheAll = cacheAll;
        this.backends = backends;
    }

    @Override
    public MapResult map(ProxyRequest request) {
        String uri = request.getUri();
        if (uri.contains("not-found")) {
            return MapResult.notFound(MapResult.NO_ROUTE);
        } else if (uri.contains("debug")) {
            return MapResult.builder()
                    .action(MapResult.Action.SYSTEM)
                    .routeId(MapResult.NO_ROUTE)
                    .build();
        } else if (cacheAll) {
            return MapResult.builder()
                    .host(host)
                    .port(port)
                    .action(MapResult.Action.CACHE)
                    .routeId(MapResult.NO_ROUTE)
                    .build();
        } else {
            return MapResult.builder()
                    .host(host)
                    .port(port)
                    .action(MapResult.Action.PROXY)
                    .routeId(MapResult.NO_ROUTE)
                    .build();
        }
    }

    @Override
    public Map<String, BackendConfiguration> getBackends() {
        return backends;
    }

    @Override
    public List<RouteConfiguration> getRoutes() {
        return routes;
    }

    @Override
    public List<ActionConfiguration> getActions() {
        return actions;
    }

    @Override
    public List<DirectorConfiguration> getDirectors() {
        return directors;
    }

    @Override
    public List<CustomHeader> getHeaders() {
        return headers;
    }

    @Override
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {

    }

}
