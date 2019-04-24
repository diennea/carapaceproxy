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

import java.util.List;
import org.carapaceproxy.server.mapper.CustomHeader;

public class MapResult {

    public static final String NO_ROUTE = "-";

    public final String host;
    public final int port;
    public final Action action;
    public final String routeid;
    public int errorcode;
    public String resource;    
    public final List<CustomHeader> customHeaders;

    public MapResult(String host, int port, Action action, String routeid, List<CustomHeader> customHeaders) {
        this.host = host;
        this.port = port;
        this.action = action;
        this.routeid = routeid;
        this.customHeaders = customHeaders;
    }

    public static MapResult NOT_FOUND(String routeid) {
        return new MapResult(null, 0, Action.NOTFOUND, routeid, null);
    }

    public static MapResult INTERNAL_ERROR(String routeid) {
        return new MapResult(null, 0, Action.INTERNAL_ERROR, routeid, null);
    }

    public int getErrorcode() {
        return errorcode;
    }

    public MapResult setErrorcode(int errorcode) {
        this.errorcode = errorcode;
        return this;
    }

    public String getResource() {
        return resource;
    }

    public MapResult setResource(String resource) {
        this.resource = resource;
        return this;
    }

    @Override
    public String toString() {
        return "MapResult{" + "host=" + host + ", port=" + port + ", action=" + action + ", routeid=" + routeid + ", errorcode=" + errorcode + ", resource=" + resource + ", customHeaders=" + customHeaders + '}';
    }

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
        ACME_CHALLENGE
    }

}
