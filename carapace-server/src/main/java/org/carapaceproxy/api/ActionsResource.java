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
import java.util.stream.Collectors;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ActionConfiguration;
import static org.carapaceproxy.server.config.ActionConfiguration.TYPE_ACME_CHALLENGE;
import static org.carapaceproxy.server.config.ActionConfiguration.TYPE_CACHE;
import static org.carapaceproxy.server.config.ActionConfiguration.TYPE_PROXY;
import static org.carapaceproxy.server.config.ActionConfiguration.TYPE_REDIRECT;
import static org.carapaceproxy.server.config.ActionConfiguration.TYPE_STATIC;

/**
 * Access to configured actions
 *
 * @author paolo.venturi
 */
@Path("/actions")
@Produces("application/json")
public class ActionsResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class ActionBean {

        private final String id;
        private final String type;
        private final String description;
        private final String errorcode;
        private final String headers;

        public ActionBean(String id, String type, String desc, String headers, int errorcode) {
            this.id = id;
            this.type = type;
            this.description = desc;
            this.headers = headers;
            this.errorcode = errorcode > 0 ? (errorcode + "") : "";
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public String getHeaders() {
            return headers;
        }

        public String getErrorcode() {
            return errorcode;
        }
    }

    @GET
    public List<ActionBean> getAll() {
        final List<ActionBean> actions = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        server.getMapper().getActions().forEach(action -> {
            if (!TYPE_ACME_CHALLENGE.equals(action.getType())) {
                String headers = action.getCustomHeaders().stream().map(h -> h.getId()).collect(Collectors.joining(","));
                actions.add(new ActionBean(action.getId(), action.getType(), computeDescription(action), headers, action.getErrorcode()));
            }
        });

        return actions;
    }

    private static String computeDescription(ActionConfiguration action) {
        switch (action.getType()) {
            case TYPE_CACHE:
            case TYPE_PROXY:
                return "proxy to director: " + action.getDirector();
            case TYPE_STATIC: {
                return "serve file: " + action.getFile();
            }
            case TYPE_REDIRECT: {
                String redirectLocation = action.getRedirectLocation();
                if (redirectLocation.isEmpty()) {
                    redirectLocation = emptyAsOther(action.getRedirectProto(), "proto") + "://"
                            + emptyAsOther(action.getRedirectHost(), "hostname") + ":"
                            + emptyAsOther(
                                    action.getRedirectPort() > 0 ? (action.getRedirectPort() + "") : "",
                                    "port"
                            ) + "/"
                            + emptyAsOther(action.getRedirectPath(), "path");
                }
                return "redirect to: " + redirectLocation;
            }
            default:
                throw new IllegalStateException("For action " +  action);
        }
    }

    static private String emptyAsOther(String value, String other) {
        return value.isEmpty() ? other : value;
    }

}
