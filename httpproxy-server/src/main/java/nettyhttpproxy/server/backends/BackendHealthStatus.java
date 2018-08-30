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
package nettyhttpproxy.server.backends;

import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.server.config.BackendConfiguration;

/**
 * Health of a backend
 *
 * @author enrico.olivelli
 */
public class BackendHealthStatus {

    private static final Logger LOG = Logger.getLogger(BackendHealthStatus.class.getName());

    private final String id;
    private final BackendConfiguration conf;

    private volatile boolean reportedAsUnreachable;
    private long reportedAsUnreachableTs;

    private long lastProbeTs;
    private boolean lastProbeSuccess;
    private String lastProbeResult;

    public BackendHealthStatus(String id, BackendConfiguration conf) {
        this.id = id;
        this.conf = conf;
    }

    public String getId() {
        return id;
    }

    public BackendConfiguration getConf() {
        return conf;
    }

    public boolean isLastProbeSuccess() {
        return lastProbeSuccess;
    }

    public void setLastProbeSuccess(boolean lastProbeSuccess) {
        this.lastProbeSuccess = lastProbeSuccess;
    }

    public long getLastProbeTs() {
        return lastProbeTs;
    }

    public void setLastProbeTs(long lastProbeTs) {
        this.lastProbeTs = lastProbeTs;
    }

    public String getLastProbeResult() {
        return lastProbeResult;
    }

    public void setLastProbeResult(String lastProbeResult) {
        this.lastProbeResult = lastProbeResult;
    }

    public boolean isReportedAsUnreachable() {
        return reportedAsUnreachable;
    }

    public void setReportedAsUnreachable(boolean reportedAsUnreachable) {
        this.reportedAsUnreachable = reportedAsUnreachable;
    }

    public long getReportedAsUnreachableTs() {
        return reportedAsUnreachableTs;
    }

    public void setReportedAsUnreachableTs(long reportedAsUnreachableTs) {
        this.reportedAsUnreachableTs = reportedAsUnreachableTs;
    }

    void reportAsUnreachable(long timestamp) {
        LOG.log(Level.INFO, "{0}: reportAsUnreachable {1}", new Object[]{id, new java.sql.Timestamp(timestamp)});
        reportedAsUnreachableTs = timestamp;
        reportedAsUnreachable = true;
    }

    void reportAsReachable() {
        reportedAsUnreachable = false;
        reportedAsUnreachableTs = 0;
    }

    public boolean isAvailable() {
        return !reportedAsUnreachable;
    }

    @Override
    public String toString() {
        return "BackendHealthStatus{" + "id=" + id + ", conf=" + conf + ", reportedAsUnreachable=" + reportedAsUnreachable + ", reportedAsUnreachableTs=" + reportedAsUnreachableTs + ", lastProbeTs=" + lastProbeTs + ", lastProbeSuccess=" + lastProbeSuccess + ", lastProbeResult=" + lastProbeResult + '}';
    }

}
