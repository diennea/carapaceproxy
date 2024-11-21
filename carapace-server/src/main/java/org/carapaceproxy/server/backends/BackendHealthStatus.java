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

import java.sql.Timestamp;
import java.time.Duration;
import org.carapaceproxy.core.EndpointKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health of a backend
 *
 * @author enrico.olivelli
 */
public class BackendHealthStatus {

    // todo replace this with a property of some kind
    public static final long WARMUP_MILLIS = Duration.ofMinutes(1).toMillis();

    private static final Logger LOG = LoggerFactory.getLogger(BackendHealthStatus.class);

    private final EndpointKey hostPort;
    private volatile Status status;
    private volatile long lastUnreachableTs;
    private volatile long lastReachableTs;
    private volatile BackendHealthCheck lastProbe;

    public BackendHealthStatus(final EndpointKey hostPort) {
        this.hostPort = hostPort;
        // todo using DOWN would break BasicStandardEndpointMapperTest, UnreachableBackendTest, and StuckRequestsTest
        this.status = Status.COLD;
        this.lastUnreachableTs = System.currentTimeMillis();
    }

    public EndpointKey getHostPort() {
        return this.hostPort;
    }

    public BackendHealthCheck getLastProbe() {
        return this.lastProbe;
    }

    public void setLastProbe(final BackendHealthCheck lastProbe) {
        this.lastProbe = lastProbe;
    }

    public Status getStatus() {
        return status;
    }

    public long getLastUnreachableTs() {
        return lastUnreachableTs;
    }

    public void reportAsUnreachable(final long timestamp, final String cause) {
        LOG.info("{}: reportAsUnreachable {} cause {}", hostPort, new Timestamp(timestamp), cause);
        this.lastUnreachableTs = timestamp;
        this.status = Status.DOWN;
    }

    public void reportAsReachable(final long timestamp) {
        this.lastReachableTs = timestamp;
        if (this.lastReachableTs - this.lastUnreachableTs >= WARMUP_MILLIS) {
            this.status = Status.STABLE;
        } else {
            this.status = Status.COLD;
        }
    }

    @Override
    public String toString() {
        return "BackendHealthStatus{"
                + " hostPort=" + this.hostPort
                + ", status=" + this.status
                + ", lastUnreachableTs=" + this.lastUnreachableTs
                + ", lastReachableTs=" + this.lastReachableTs
                + ", lastProbe=" + this.lastProbe
                + '}';
    }

    /**
     * The enum models a simple status of the backend.
     */
    public enum Status {
        /**
         * The backend is unreachable.
         */
        DOWN,
        /**
         * The backend is reachable, but not since long.
         * When in this safe, it is reasonable to assume that it is still warming-up,
         * so it would be a sensible decision not to overload it with requests.
         */
        COLD,
        /**
         * The backend is reachable and was so since a reasonably-long time.
         */
        STABLE
    }
}
