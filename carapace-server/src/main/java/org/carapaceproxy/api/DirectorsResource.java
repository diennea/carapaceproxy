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

/**
 * Access to configured directors
 *
 * @author paolo.venturi
 */
@Path("/directors")
@Produces("application/json")
public class DirectorsResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class DirectorBean {
        private final String id;
        private final List<String> backends;

        public DirectorBean(String id, List<String> backends) {
            this.id = id;
            this.backends = backends;
        }

        public String getId() {
            return id;
        }

        public List<String> getBackends() {
            return backends;
        }
    }

    @GET
    public List<DirectorBean> getAll() {
        final List<DirectorBean> directors = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        server.getMapper().getDirectors().forEach(director -> {
            directors.add(new DirectorBean(director.getId(), director.getBackends()));
        });

        return directors;
    }

}
