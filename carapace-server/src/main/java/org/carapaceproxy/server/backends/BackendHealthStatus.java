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
import java.util.concurrent.atomic.AtomicInteger;
import org.carapaceproxy.core.EndpointKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health of a backend
 *
 * @author enrico.olivelli
 */
public class BackendHealthStatus {

    // todo replace this with a configurable property of some kind
    public static final long WARMUP_MILLIS = Duration.ofMinutes(1).toMillis();

    private static final Logger LOG = LoggerFactory.getLogger(BackendHealthStatus.class);

    private final EndpointKey hostPort;
    private final AtomicInteger connections;

    private volatile Status status;
    private volatile long unreachableSince;
    private volatile long lastUnreachable;
    private volatile long lastReachable;
    private volatile BackendHealthCheck lastProbe;

    public BackendHealthStatus(final EndpointKey hostPort) {
        this.hostPort = hostPort;
        // todo cannot start with a DOWN backend (+ current time) as it would break:
        //  - BasicStandardEndpointMapperTest,
        //  - UnreachableBackendTest,
        //  - StuckRequestsTest,
        //  - HealthCheckTest
        // we assume that the backend is reachable when status is created
        this.status = Status.COLD;
        this.unreachableSince = 0L;
        final long created = System.currentTimeMillis();
        this.lastUnreachable = created;
        this.lastReachable = created;
        this.connections = new AtomicInteger();
    }

    public long getUnreachableSince() {
        return unreachableSince;
    }

    public long getLastUnreachable() {
        return lastUnreachable;
    }

    public long getLastReachable() {
        return lastReachable;
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

    public void reportAsUnreachable(final long timestamp, final String cause) {
        LOG.info("{}: reportAsUnreachable {}, cause {}", hostPort, new Timestamp(timestamp), cause);
        if (this.status != Status.DOWN) {
            this.status = Status.DOWN;
            this.unreachableSince = timestamp;
        }
        this.lastUnreachable = timestamp;
        this.connections.set(0);
    }

    public void reportAsReachable(final long timestamp) {
        LOG.info("{}: reportAsUnreachable {}", hostPort, new Timestamp(timestamp));
        switch (this.status) {
            case DOWN:
                this.status = Status.COLD;
                this.unreachableSince = 0;
                this.lastReachable = timestamp;
                break;
            case COLD:
                this.lastReachable = timestamp;
                if (this.lastReachable - this.lastUnreachable > WARMUP_MILLIS) {
                    this.status = Status.STABLE;
                }
                break;
            case STABLE:
                this.lastReachable = timestamp;
                break;
        }
    }

    @Override
    public String toString() {
        return "BackendHealthStatus{"
                + " hostPort=" + this.hostPort
                + ", connections=" + this.connections
                + ", status=" + this.status
                + ", unreachableSince=" + this.unreachableSince
                + ", unreachableUntil=" + this.lastUnreachable
                + ", lastReachable=" + this.lastReachable
                + ", lastProbe=" + this.lastProbe
                + '}';
    }

    public int getConnections() {
        return this.connections.get();
    }

    public void incrementConnections() {
        this.connections.incrementAndGet();
    }

    public void decrementConnections() {
        if (this.connections.getAcquire() > 0) {
            this.connections.decrementAndGet();
        }
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
