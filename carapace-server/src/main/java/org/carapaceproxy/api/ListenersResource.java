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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import lombok.Data;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.HostPort;
import reactor.netty.DisposableChannel;

/**
 * Access to listeners
 *
 * @author matteo.minardi
 */
@Path("/listeners")
@Produces("application/json")
public class ListenersResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    @Data
    public static final class ListenerBean {

        private final String host;
        private final int port;
        private final boolean ssl;
        private final String sslCiphers;
        private final Set<String> sslProtocols;
        private final String defaultCertificate;
        private final int totalRequests;

    }

    @GET
    public Map<String, ListenerBean> getAllListeners() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");

        Map<String, ListenerBean> map = new HashMap<>();
        for (Map.Entry<HostPort, DisposableChannel> hostPortDisposableChannelEntry : server.getListeners().getListeningChannels().entrySet()) {
            // todo
            /* NetworkListenerConfiguration config = hostPortDisposableChannelEntry.getValue().getConfig();
            int port = hostPortDisposableChannelEntry.getKey().port();
            ListenerBean bean = new ListenerBean(
                    config.getHost(),
                    port,
                    config.isSsl(),
                    config.getSslCiphers(),
                    config.getSslProtocols(),
                    config.getDefaultCertificate(),
                    (int) hostPortDisposableChannelEntry.getValue().getTotalRequests().get()
            );
            Map.Entry<String, ListenerBean> apply = Map.entry(EndpointKey.make(config.getHost(), port).getHostPort(), bean);
            if (map.put(apply.getKey(), apply.getValue()) != null) {
                throw new IllegalStateException("Duplicate key");
            } */
        }
        return map;
    }

}
