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
        private final String director;
        private final String file;
        private final int errorcode;

        public ActionBean(String id, String type, String director, String file, int errorcode) {
            this.id = id;
            this.type = type;
            this.file = file;
            this.director = director;
            this.errorcode = errorcode;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getDirector() {
            return director;
        }

        public String getFile() {
            return file;
        }

        public int getErrorcode() {
            return errorcode;
        }
    }

    @GET
    public List<ActionBean> getAll() {
        final List<ActionBean> actions = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        server.getMapper().getActions().forEach(action -> {
            actions.add(new ActionBean(action.getId(), action.getType(), action.getDirector(), action.getFile(), action.getErrorcode()));
        });

        return actions;
    }

}
