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

import static org.assertj.core.api.Assertions.assertThat;

import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.server.backends.BackendHealthStatus.Status;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link BackendHealthStatus} DOWN/COLD/STABLE state machine, driven with explicit
 * timestamps (no clock, no sleeps). Covers the COLD&rarr;STABLE warmup transition that the probe-driven
 * HealthCheckIT never observes.
 */
class BackendHealthStatusTest {

    private static final EndpointKey KEY = EndpointKey.make("localhost", 8080);

    @Test
    void freshStatusIsCold() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        assertThat(status.getStatus()).isEqualTo(Status.COLD);
        assertThat(status.getUnreachableSince()).isZero();
    }

    @Test
    void reportAsUnreachableMovesToDown() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        status.reportAsUnreachable(1000, "500 error");
        assertThat(status.getStatus()).isEqualTo(Status.DOWN);
        assertThat(status.getUnreachableSince()).isEqualTo(1000);
        assertThat(status.getLastUnreachable()).isEqualTo(1000);
    }

    @Test
    void furtherUnreachableKeepsDownAndPreservesUnreachableSince() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        status.reportAsUnreachable(1000, "500 error");
        status.reportAsUnreachable(2000, "still down");
        assertThat(status.getStatus()).isEqualTo(Status.DOWN);
        assertThat(status.getUnreachableSince()).isEqualTo(1000); // preserved, not reset
        assertThat(status.getLastUnreachable()).isEqualTo(2000); // refreshed
    }

    @Test
    void reachableFromDownMovesToCold() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        status.reportAsUnreachable(1000, "500 error");
        status.reportAsReachable(3000);
        assertThat(status.getStatus()).isEqualTo(Status.COLD);
        assertThat(status.getUnreachableSince()).isZero();
        assertThat(status.getLastReachable()).isEqualTo(3000);
    }

    @Test
    void coldToStableOnlyAfterWarmupElapses() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        // Establish a deterministic lastUnreachable=1000, then come back COLD (DOWN->COLD keeps lastUnreachable).
        status.reportAsUnreachable(1000, "500 error");
        status.reportAsReachable(1001);
        assertThat(status.getStatus()).isEqualTo(Status.COLD);
        assertThat(status.getLastUnreachable()).isEqualTo(1000);

        // Strict '>': exactly warmup after lastUnreachable stays COLD.
        status.reportAsReachable(1000 + 500);
        assertThat(status.getStatus()).isEqualTo(Status.COLD);

        // One past warmup flips to STABLE.
        status.reportAsReachable(1000 + 500 + 1);
        assertThat(status.getStatus()).isEqualTo(Status.STABLE);
    }

    @Test
    void stableStaysStableOnFurtherProbes() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        status.reportAsUnreachable(1000, "500 error");
        status.reportAsReachable(1001);
        status.reportAsReachable(1000 + 500 + 1);
        assertThat(status.getStatus()).isEqualTo(Status.STABLE);

        status.reportAsReachable(9999);
        assertThat(status.getStatus()).isEqualTo(Status.STABLE);
    }

    @Test
    void freshBackendJumpsStraightToStable() {
        // A fresh backend seeds lastUnreachable to construction time, so the first successful probe
        // arriving more than warmupPeriod later flips it straight to STABLE, bypassing warmup entirely.
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        long created = status.getLastUnreachable();
        status.reportAsReachable(created + 500 + 1);
        assertThat(status.getStatus()).isEqualTo(Status.STABLE);
    }

    @Test
    void connectionsIncrementDecrementAndClampAtZero() {
        BackendHealthStatus status = new BackendHealthStatus(KEY, 500);
        assertThat(status.getConnections()).isZero();
        status.incrementConnections();
        status.incrementConnections();
        assertThat(status.getConnections()).isEqualTo(2);
        status.decrementConnections();
        assertThat(status.getConnections()).isOne();
        status.decrementConnections();
        status.decrementConnections(); // already at 0, must clamp
        assertThat(status.getConnections()).isZero();
    }
}
