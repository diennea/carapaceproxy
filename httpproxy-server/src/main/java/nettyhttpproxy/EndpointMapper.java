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
package nettyhttpproxy;

import httpproxy.server.certiticates.DynamicCertificatesManager;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Map;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.configstore.ConfigurationStore;
import nettyhttpproxy.server.RequestHandler;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.config.BackendConfiguration;
import nettyhttpproxy.server.config.ConfigurationNotValidException;

/**
 * Maps requests to a remote HTTP server
 *
 * @author enrico.olivelli
 */
public abstract class EndpointMapper {

    public abstract Map<String, BackendConfiguration> getBackends();

    public abstract MapResult map(HttpRequest request, String userId, String sessionId, BackendHealthManager backendHealthManager, RequestHandler requestHandler);

    public MapResult mapDefaultInternalError(HttpRequest request, String routeid) {
        return MapResult.INTERNAL_ERROR(routeid);
    }

    public MapResult mapDefaultPageNotFound(HttpRequest request, String routeid) {
        return MapResult.NOT_FOUND(routeid);
    }

    public void endpointFailed(EndpointKey key, Throwable error) {
    }

    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
    }

    public void setDynamicCertificateManager(DynamicCertificatesManager manager) {
    }

}
