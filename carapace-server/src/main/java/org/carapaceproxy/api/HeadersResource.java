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

import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.mapper.CustomHeader;

/**
 * Access to configured actions
 *
 * @author paolo.venturi
 */
@Path("/headers")
@Produces("application/json")
public class HeadersResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class HeaderBean {

        private final String id;
        private final String name;
        private final String value;
        private final String mode;

        public HeaderBean(String id, String name, String value, String mode) {
            this.id = id;
            this.name = name;
            this.value = value;
            this.mode = mode;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getMode() {
            return mode;
        }
    }

    @GET
    public List<HeaderBean> getAll() {
        final List<HeaderBean> headers = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        server.getMapper().getHeaders().forEach(header -> {
            headers.add(new HeaderBean(header.getId(), header.getName(), header.getValue(), modeToString(header.getMode())));
        });

        return headers;
    }

    static String modeToString(CustomHeader.HeaderMode mode) {
        switch (mode) {
            case ADD:
                return "add";
            case SET:
                return "set";
            case REMOVE:
                return "remove";
            default:
                return "not defined";
        }
    }

}
