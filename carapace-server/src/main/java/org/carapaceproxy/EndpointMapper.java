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

import io.netty.handler.codec.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.StaticContentsManager;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ActionConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.DirectorConfiguration;
import org.carapaceproxy.server.config.RouteConfiguration;
import org.carapaceproxy.server.mapper.CustomHeader;

/**
 * Maps requests to a remote HTTP server
 *
 * @author enrico.olivelli
 */
public abstract class EndpointMapper {

    public abstract Map<String, BackendConfiguration> getBackends();

    public abstract List<RouteConfiguration> getRoutes();

    public abstract List<ActionConfiguration> getActions();

    public abstract List<DirectorConfiguration> getDirectors();

    public abstract List<CustomHeader> getHeaders();

    public abstract MapResult map(HttpRequest request, String userId, String sessionId, BackendHealthManager backendHealthManager, RequestHandler requestHandler);

    public SimpleHTTPResponse mapPageNotFound(String routeid) {
        return SimpleHTTPResponse.NOT_FOUND(StaticContentsManager.DEFAULT_NOT_FOUND);
    }

    public SimpleHTTPResponse mapInternalError(String routeid) {
        return SimpleHTTPResponse.INTERNAL_ERROR(StaticContentsManager.DEFAULT_INTERNAL_SERVER_ERROR);
    }

    public void endpointFailed(EndpointKey key, Throwable error) {
    }

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
    }

    public void setDynamicCertificateManager(DynamicCertificatesManager manager) {
    }

}
