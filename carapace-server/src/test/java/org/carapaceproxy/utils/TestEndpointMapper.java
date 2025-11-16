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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;

public class TestEndpointMapper extends EndpointMapper implements EndpointMapper.Factory {

    private final boolean proxyCache;
    private final BackendConfiguration backend;

    public TestEndpointMapper(final HttpProxyServer ignoredServer) {
        this("localhost", 0); // required for reflective construction
    }

    public TestEndpointMapper(final String host, final int port) {
        this(host, port, false, false);
    }

    public TestEndpointMapper(final String host, final int port, final boolean proxyCache, final boolean ssl) {
        this(new BackendConfiguration(host, host, port, "/index.html", -1, ssl), proxyCache);
    }

    public TestEndpointMapper(final BackendConfiguration backend, final boolean proxyCache) {
        super(null);
        this.proxyCache = proxyCache;
        this.backend = backend;
    }

    @Override
    public MapResult map(final ProxyRequest request) {
        final String headerHost = request.getRequestHostname();
        if (StringUtils.isBlank(headerHost) || !StandardEndpointMapper.isValidHostAndPort(headerHost)) {
            return MapResult.badRequest();
        }

        final String uri = request.getUri();
        if (uri.contains("not-found")) {
            return MapResult.notFound(MapResult.NO_ROUTE);
        }
        if (uri.contains("debug")) {
            return MapResult.builder()
                    .action(MapResult.Action.SYSTEM)
                    .routeId(MapResult.NO_ROUTE)
                    .build();
        }
        final BackendHealthStatus healthStatus = new BackendHealthStatus(request.getListener(), 0);
        // warmupPeriod = 0 & status = COLD -> new status = STABLE
        healthStatus.reportAsReachable(System.currentTimeMillis());
        if (proxyCache) {
            return MapResult.builder()
                    .host(backend.host())
                    .port(backend.port())
                    .ssl(backend.ssl())
                    .action(MapResult.Action.CACHE)
                    .routeId(MapResult.NO_ROUTE)
                    .healthStatus(healthStatus)
                    .build();
        }

        return MapResult.builder()
                .host(backend.host())
                .port(backend.port())
                .ssl(backend.ssl())
                .action(MapResult.Action.PROXY)
                .routeId(MapResult.NO_ROUTE)
                .healthStatus(healthStatus)
                .build();
    }

    @Override
    public SequencedMap<String, BackendConfiguration> getBackends() {
        final SequencedMap<String, BackendConfiguration> backends = new LinkedHashMap<>();
        backends.put(backend.id(), backend);
        return Collections.unmodifiableSequencedMap(backends);
    }

    @Override
    public List<RouteConfiguration> getRoutes() {
        return List.of();
    }

    @Override
    public List<ActionConfiguration> getActions() {
        return List.of();
    }

    @Override
    public SequencedMap<String, DirectorConfiguration> getDirectors() {
        return Collections.emptySortedMap();
    }

    @Override
    public List<CustomHeader> getHeaders() {
        return List.of();
    }

    @Override
    public void configure(ConfigurationStore properties) {
    }

    @Override
    public EndpointMapper build(final HttpProxyServer httpProxyServer) {
        return this;
    }
}
