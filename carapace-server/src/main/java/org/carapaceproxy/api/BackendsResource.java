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
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.backends.BackendHealthCheck;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;

/**
 * Access to backends status
 *
 * @author enrico.olivelli
 */
@Path("/backends")
@Produces("application/json")
public class BackendsResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class BackendBean {

        private String host;
        private int port;
        private long openConnections;
        private long totalRequests;
        private long lastActivityTs;

        private boolean isAvailable;
        private boolean reportedAsUnreachable;
        private long reportedAsUnreachableTs;
        private String lastProbePath;
        private long lastProbeTs;
        private boolean lastProbeSuccess;
        private String httpResponse;
        private String httpBody;

        public BackendBean(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public long getOpenConnections() {
            return openConnections;
        }

        public long getTotalRequests() {
            return totalRequests;
        }

        public long getLastActivityTs() {
            return lastActivityTs;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean isIsAvailable() {
            return isAvailable;
        }

        public boolean isReportedAsUnreachable() {
            return reportedAsUnreachable;
        }

        public long getReportedAsUnreachableTs() {
            return reportedAsUnreachableTs;
        }

        public String getLastProbePath() {
            return lastProbePath;
        }

        public long getLastProbeTs() {
            return lastProbeTs;
        }

        public boolean isLastProbeSuccess() {
            return lastProbeSuccess;
        }

        public String getHttpResponse() {
            return httpResponse;
        }

        public String getHttpBody() {
            return httpBody;
        }

    }

    @GET
    public Map<String, BackendBean> getAll() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");

        BackendHealthManager backendHealthManager = server.getBackendHealthManager();
        ConnectionsManagerStats stats = server.getConnectionsManager().getStats();
        Map<String, BackendHealthStatus> backendsSnapshot = backendHealthManager.getBackendsSnapshot();

        Map<String, BackendBean> res = new HashMap<>();
        for (Map.Entry<String, BackendHealthStatus> bb : backendsSnapshot.entrySet()) {
            EndpointKey key = EndpointKey.make(bb.getKey());
            EndpointStats epstats = stats.getEndpointStats(key);
            BackendBean bean = new BackendBean(key.getHost(), key.getPort());
            if (epstats != null) {
                bean.openConnections = epstats.getOpenConnections().longValue();
                bean.totalRequests = epstats.getTotalRequests().longValue();
                bean.lastActivityTs = epstats.getLastActivity().longValue();
            }
            BackendHealthStatus bhs = bb.getValue();
            if (bhs != null) {
                bean.isAvailable = bhs.isAvailable();
                bean.reportedAsUnreachable = bhs.isReportedAsUnreachable();
                bean.reportedAsUnreachableTs = bhs.getReportedAsUnreachableTs();
                BackendHealthCheck lastProbe = bhs.getLastProbe();
                if (lastProbe != null) {
                    bean.lastProbePath = lastProbe.getPath();
                    bean.lastProbeTs = lastProbe.getEndTs();
                    bean.lastProbeSuccess = lastProbe.isOk();
                    bean.httpResponse = lastProbe.getHttpResponse();
                    bean.httpBody = lastProbe.getHttpBody();
                }
            }
            res.put(bb.getKey(), bean);
        }

        return res;
    }

}
