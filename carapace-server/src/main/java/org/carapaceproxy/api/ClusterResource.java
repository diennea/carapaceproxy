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
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.server.HttpProxyServer;

/**
 * Access to configured actions
 *
 * @author paolo.venturi
 */
@Path("/cluster")
@Produces("application/json")
public class ClusterResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class PeerBean {

        private final String id;
        private final String description;
        private final Map<String, String> info;

        public PeerBean(String id, String descrption, Map<String, String> info) {
            this.id = id;
            this.description = descrption;
            this.info = info;
        }

        public String getId() {
            return id;
        }

        public String getDescrption() {
            return description;
        }

        public Map<String, String> getInfo() {
            return info;
        }
    }

    private PeerBean getPeer(String peerId, GroupMembershipHandler handler) {
        return new PeerBean(peerId, handler.describePeer(peerId), handler.loadInfoForPeer(peerId));
    }

    @GET
    @Path("peers")
    public List<PeerBean> getAll() {
        final List<PeerBean> peers = new ArrayList();
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        GroupMembershipHandler handler = server.getGroupMembershipHandler();
        handler.getPeers().forEach(p-> peers.add(getPeer(p, handler)));

        return peers;
    }

    @GET
    @Path("peers/current")
    public PeerBean getCurrentPeer() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        GroupMembershipHandler handler = server.getGroupMembershipHandler();

        return getPeer(handler.getCurrentPeer(), handler);
    }

    @GET
    @Path("peers/{peerId}")
    public PeerBean getPeer(@PathParam("peerId") String peerId) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        GroupMembershipHandler handler = server.getGroupMembershipHandler();

        return getPeer(peerId, handler);
    }

}
