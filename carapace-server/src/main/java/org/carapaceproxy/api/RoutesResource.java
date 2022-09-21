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
import org.carapaceproxy.core.HttpProxyServer;
import static org.carapaceproxy.server.mapper.StandardEndpointMapper.ACME_CHALLENGE_ROUTE_ACTION_ID;

/**
 * Access to configured routes
 *
 * @author paolo.venturi
 */
@Path("/routes")
@Produces("application/json")
public class RoutesResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class RouteBean {
        private final String id;
        private final String action;
        private final String errorAction;
        private final boolean enabled;
        private final String matcher;
        private final String maintenanceAction;

        public RouteBean(String id, String action, String errorAction, boolean enabled, String matcher, String maintenanceAction) {
            this.id = id;
            this.action = action;
            this.errorAction = errorAction;
            this.enabled = enabled;
            this.matcher = matcher;
            this.maintenanceAction = maintenanceAction;
        }

        public String getId() {
            return id;
        }

        public String getAction() {
            return action;
        }

        public String getErrorAction() {
            return errorAction;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getMatcher() {
            return matcher;
        }

        public String getMaintenanceAction() { return maintenanceAction; }
    }

    @GET
    public List<RouteBean> getAll() {
        final List<RouteBean> routes = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        server.getMapper().getRoutes().stream()
                .filter(r -> !r.getId().equals(ACME_CHALLENGE_ROUTE_ACTION_ID))
                .forEach(route -> {
                    routes.add(new RouteBean(
                            route.getId(),
                            route.getAction(),
                            route.getErrorAction(),
                            route.isEnabled(),
                            route.getMatcher().getDescription(),
                            route.getMaintenanceModeAction()));
                });

        return routes;
    }

}
