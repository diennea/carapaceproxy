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
package nettyhttpproxy.api;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.backends.BackendHealthStatus;
import nettyhttpproxy.utils.StringUtils;

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

        private static final String STATUS_UP = "UP";
        private static final String STATUS_DOWN = "DOWN";

        private String host;
        private int port;
        private long openConnections;
        private long totalRequests;
        private long lastActivityTs;

        private boolean isAvailable;
        private boolean reportedAsUnreachable;
        private long reportedAsUnreachableTs;
        private long lastProbeTs;
        private String lastProbeSuccess;
        private String lastProbeResult;

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

        public long getLastProbeTs() {
            return lastProbeTs;
        }

        public String getLastProbeSuccess() {
            return lastProbeSuccess;
        }

        public String getLastProbeResult() {
            return lastProbeResult;
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
                bean.lastProbeTs = bhs.getLastProbeTs();
                bean.lastProbeSuccess = bhs.isLastProbeSuccess() ? BackendBean.STATUS_UP : BackendBean.STATUS_DOWN;
                
                if (bhs.getLastProbeResult() != null) {
                    String result = bhs.getLastProbeResult().replaceAll("(\r\n|\n)", "<br>");
                    bean.lastProbeResult = StringUtils.htmlEncode(result);
                } else {
                    bean.lastProbeResult = "";
                }
            }
            res.put(bb.getKey(), bean);
        }

        return res;
    }

}
