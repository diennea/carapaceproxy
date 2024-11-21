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
package org.carapaceproxy.server.mapper;

import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import org.carapaceproxy.SimpleHTTPResponse;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.core.StaticContentsManager;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;

/**
 * Maps requests to a remote HTTP server
 *
 * @author enrico.olivelli
 */
public abstract class EndpointMapper {

    private HttpProxyServer parent;

    /**
     * Get the pool of {@link BackendConfiguration backends} to choose from.
     *
     * @return a map where the key is the {@link BackendConfiguration#id() id of the backend},
     * and sorted according to configuration order
     */
    public abstract SequencedMap<String, BackendConfiguration> getBackends();

    /**
     * Get all the available {@link RouteConfiguration routes}.
     *
     * @return a list of routes, sorted according to configuration order
     */
    public abstract List<RouteConfiguration> getRoutes();

    /**
     * Get all the configured {@link ActionConfiguration actions}.
     *
     * @return a list of actions, sorted according to configuration order
     */
    public abstract List<ActionConfiguration> getActions();

    /**
     * Get all the configured {@link DirectorConfiguration directors} for the {@link BackendConfiguration backends}.
     *
     * @return a list of directors, sorted according to configuration order
     */
    public abstract List<DirectorConfiguration> getDirectors();

    /**
     * Get all the {@link CustomHeader custom headers} that can be applied to the requests.
     *
     * @return a list of custom headers, sorted according to configuration order
     */
    public abstract List<CustomHeader> getHeaders();

    /**
     * Process a request for a {@link ProxyRequestsManager}.
     * <p>
     * According to the incoming request and the underlying configuration,
     * it computes an {@link MapResult#getAction() action} to execute on a specific {@link MapResult#routeId route}.
     *
     * @param request the request of a resource to be proxied
     * @return the result of the mapping process
     * @see ProxyRequestsManager#processRequest(ProxyRequest)
     */
    public abstract MapResult map(ProxyRequest request);

    public SimpleHTTPResponse mapPageNotFound(String routeId) {
        return SimpleHTTPResponse.notFound(StaticContentsManager.DEFAULT_NOT_FOUND);
    }

    public SimpleHTTPResponse mapInternalError(String routeId) {
        return SimpleHTTPResponse.internalError(StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR);
    }

    public SimpleHTTPResponse mapServiceUnavailableError(String routeId) {
        return SimpleHTTPResponse.serviceUnavailable(StaticContentsManager.DEFAULT_SERVICE_UNAVAILABLE_ERROR);
    }

    public SimpleHTTPResponse mapMaintenanceMode(String routeId) {
        return SimpleHTTPResponse.internalError(StaticContentsManager.DEFAULT_MAINTENANCE_MODE_ERROR);
    }

    public SimpleHTTPResponse mapBadRequest() {
        return SimpleHTTPResponse.badRequest(StaticContentsManager.DEFAULT_BAD_REQUEST);
    }

    public abstract void configure(ConfigurationStore properties) throws ConfigurationNotValidException;

    public final void setParent(final HttpProxyServer parent) {
        this.parent = parent;
    }

    protected final DynamicCertificatesManager getDynamicCertificatesManager() {
        Objects.requireNonNull(parent);
        return parent.getDynamicCertificatesManager();
    }

    protected final BackendHealthManager getBackendHealthManager() {
        Objects.requireNonNull(parent);
        return parent.getBackendHealthManager();
    }

    protected final RuntimeServerConfiguration getCurrentConfiguration() {
        Objects.requireNonNull(parent);
        return parent.getCurrentConfiguration();
    }
}
