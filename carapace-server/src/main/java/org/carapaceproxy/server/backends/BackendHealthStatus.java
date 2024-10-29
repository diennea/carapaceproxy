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
package org.carapaceproxy.server.backends;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.core.EndpointKey;

/**
 * Health of a backend
 *
 * @author enrico.olivelli
 */
public class BackendHealthStatus {

    private static final Logger LOG = Logger.getLogger(BackendHealthStatus.class.getName());

    private final EndpointKey hostPort;

    private volatile boolean reportedAsUnreachable;
    private long reportedAsUnreachableTs;

    private BackendHealthCheck lastProbe;

    public BackendHealthStatus(EndpointKey hostPort) {
        this.hostPort = hostPort;
    }

    public EndpointKey getHostPort() {
        return hostPort;
    }

    public BackendHealthCheck getLastProbe() {
        return lastProbe;
    }

    public void setLastProbe(BackendHealthCheck lastProbe) {
        this.lastProbe = lastProbe;
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
        LOG.log(Level.INFO, "{0}: reportAsUnreachable {1}", new Object[]{hostPort, new java.sql.Timestamp(timestamp)});
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
        return "BackendHealthStatus{" + "hostPort=" + hostPort + ", reportedAsUnreachable=" + reportedAsUnreachable + ", reportedAsUnreachableTs=" + reportedAsUnreachableTs + '}';
    }

}
