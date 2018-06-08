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

import io.netty.handler.codec.http.HttpRequest;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.backends.BackendHealthManager;

/**
 * Maps requests to a remote HTTP server
 *
 * @author enrico.olivelli
 */
public abstract class EndpointMapper {

    public abstract MapResult map(HttpRequest request, BackendHealthManager backendHealthManager);

    public MapResult mapDefaultInternalError(HttpRequest request) {
        return MapResult.INTERNAL_ERROR;
    }

    public MapResult mapDefaultPageNotFound(HttpRequest request) {
        return MapResult.NOT_FOUND;
    }

    public void endpointFailed(EndpointKey key, Throwable error) {
    }

}
