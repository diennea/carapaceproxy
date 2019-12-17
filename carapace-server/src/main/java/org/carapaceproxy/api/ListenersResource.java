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
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.ClientConnectionHandler;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.Listeners;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;

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

    public static final class ListenerBean {

        private final String host;
        private final int port;
        private final boolean ssl;
        private final boolean ocsp;
        private final String sslCiphers;
        private final String[] sslProtocols;
        private final String defaultCertificate;
        private final int totalRequests;

        public ListenerBean(String host, int port, boolean ssl, boolean ocps, String sslCiphers, String[] sslProtocols, String defaultCertificate, int totalRequests) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
            this.ocsp = ocps;
            this.sslCiphers = sslCiphers;
            this.sslProtocols = sslProtocols;
            this.defaultCertificate = defaultCertificate;
            this.totalRequests = totalRequests;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean isSsl() {
            return ssl;
        }

        public boolean isOcsp() {
            return ocsp;
        }

        public String getSslCiphers() {
            return sslCiphers;
        }

        public String[] getSslProtocols() {
            return sslProtocols;
        }

        public String getDefaultCertificate() {
            return defaultCertificate;
        }

        public int getTotalRequests() {
            return totalRequests;
        }

    }

    @GET
    public Map<String, ListenerBean> getAllListeners() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();

        Listeners listeners = server.getListeners();
        Map<String, ListenerBean> res = new HashMap<>();
        for (NetworkListenerConfiguration listener : conf.getListeners()) {
            int port = listener.getPort() + server.getListenersOffsetPort();
            ClientConnectionHandler handler = listeners.getListenerHandler(listener.getKey());
            int totalRequests = handler == null
                    ? 0
                    : handler.getTotalRequestsCount();

            ListenerBean lisBean = new ListenerBean(
                    listener.getHost(),
                    port,
                    listener.isSsl(),
                    listener.isOcsp(),
                    listener.getSslCiphers(),
                    listener.getSslProtocols(),
                    listener.getDefaultCertificate(),
                    totalRequests
            );
            EndpointKey key = EndpointKey.make(listener.getHost(), listener.getPort());
            res.put(key.getHostPort(), lisBean);
        }

        return res;
    }

}
