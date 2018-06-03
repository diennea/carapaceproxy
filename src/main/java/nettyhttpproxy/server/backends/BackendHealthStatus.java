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

/**
 * Health of a backend
 * @author enrico.olivelli
 */
public class BackendHealthStatus {
    private final String id;
    
    private boolean reportedAsUnreachable;
    private long reportedAsUnreachableTs;
    
    private long lastProbeTs;
    private boolean lastProbeSuccess;
    private String lastProbeResult;

    public BackendHealthStatus(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
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
        reportedAsUnreachableTs = timestamp;
        reportedAsUnreachable = true;
    }

    void reportAsReachable() {
        reportedAsUnreachable = false;
        reportedAsUnreachableTs = 0;
    }
    
    
}
