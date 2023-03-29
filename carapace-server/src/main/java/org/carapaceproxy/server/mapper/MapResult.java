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
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MapResult {

    public static enum Action {
        /**
         * Proxy the request, do not cache locally
         */
        PROXY,
        /**
         * Pipe and cache if possible
         */
        CACHE,
        /**
         * Service not mapped
         */
        NOTFOUND,
        /**
         * Internal error
         */
        INTERNAL_ERROR,
        /**
         * Maintenance mode
         */
        MAINTENANCE_MODE,
        /**
         * Bad request
         */
        BAD_REQUEST,
        /**
         * Custom static message
         */
        STATIC,
        /**
         * Answer with system info
         */
        SYSTEM,
        /**
         * Answer for ACME challenge verification
         */
        ACME_CHALLENGE,
        /**
         * Redirect the request
         */
        REDIRECT
    }

    public static final String NO_ROUTE = "-";
    public static final String REDIRECT_PROTO_HTTPS = "https";
    public static final String REDIRECT_PROTO_HTTP = "http";

    public String host;
    public int port;
    public Action action;
    public String routeId;
    public int errorCode;
    public String resource;
    public List<CustomHeader> customHeaders;
    public String redirectLocation;
    public String redirectProto;
    public String redirectPath;

    public static MapResult notFound(String routeId) {
        return MapResult.builder()
                .action(Action.NOTFOUND)
                .routeId(routeId)
                .build();
    }

    public static MapResult internalError(String routeId) {
        return MapResult.builder()
                .action(Action.INTERNAL_ERROR)
                .routeId(routeId)
                .build();
    }

    public static MapResult maintenanceMode(String routeId) {
        return MapResult.builder()
                .action(Action.MAINTENANCE_MODE)
                .routeId(routeId)
                .build();
    }

    public static MapResult badRequest() {
        return MapResult.builder()
                .action(Action.BAD_REQUEST)
                .build();
    }


}
