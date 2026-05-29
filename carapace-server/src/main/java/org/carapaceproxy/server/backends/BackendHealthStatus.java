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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.carapaceproxy.core.EndpointKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health of a backend
 *
 * @author enrico.olivelli
 */
public class BackendHealthStatus {

    private static final Logger LOG = LoggerFactory.getLogger(BackendHealthStatus.class);

    private final EndpointKey hostPort;
    private final AtomicInteger connections;

    // Status + the three associated timestamps move together via a single AtomicReference so concurrent
    // reportAs(Un)Reachable callers cannot leave the four fields in a torn combination (e.g. status=DOWN
    // with unreachableSince=0). Configuration knobs (warmupPeriod) and the periodic probe handle
    // (lastProbe) stay as separate volatile fields — they aren't part of the transition's atom.
    private final AtomicReference<State> state;
    private volatile BackendHealthCheck lastProbe;
    private volatile long warmupPeriod;

    public BackendHealthStatus(final EndpointKey hostPort, final long warmupPeriod) {
        this.hostPort = hostPort;
        // we assume that the backend just became reachable when the status is created
        final long created = System.currentTimeMillis();
        this.state = new AtomicReference<>(new State(Status.COLD, 0L, created, created));
        this.connections = new AtomicInteger();
        this.warmupPeriod = warmupPeriod;
    }

    public long getUnreachableSince() {
        return state.get().unreachableSince();
    }

    public long getLastUnreachable() {
        return state.get().lastUnreachable();
    }

    public long getLastReachable() {
        return state.get().lastReachable();
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
        return state.get().status();
    }

    /**
     * Returns a coherent snapshot of the four state-machine fields ({@code status}, {@code unreachableSince},
     * {@code lastUnreachable}, {@code lastReachable}). Use this instead of calling several field-level getters
     * in a row when more than one field is needed — each individual getter is itself atomic, but two
     * consecutive getter calls can straddle a state transition and observe an inconsistent pair.
     */
    public Snapshot snapshot() {
        final State s = state.get();
        return new Snapshot(s.status(), s.unreachableSince(), s.lastUnreachable(), s.lastReachable());
    }

    public void reportAsUnreachable(final long timestamp, final String cause) {
        LOG.info("{}: reportAsUnreachable {}, cause {}", hostPort, new Timestamp(timestamp), cause);
        state.updateAndGet(s -> s.withUnreachable(timestamp));
        // The historical connections.set(0) reset has been dropped: with the inc/dec invariant
        // restored (NiccoMlt/carapaceproxy#13 — single decrement on terminal) and the atomic
        // clamp (#17), in-flight requests' decrements drain the counter naturally; SafeBackendSelector
        // already ignores `connections` for DOWN backends, so the value while DOWN is irrelevant.
    }

    public void reportAsReachable(final long timestamp) {
        LOG.debug("{}: reportAsReachable {}", hostPort, new Timestamp(timestamp));
        final long warmup = this.warmupPeriod;
        state.updateAndGet(s -> s.withReachable(timestamp, warmup));
    }

    @Override
    public String toString() {
        final State snapshot = state.get();
        return "BackendHealthStatus{"
                + " hostPort=" + this.hostPort
                + ", connections=" + this.connections
                + ", status=" + snapshot.status()
                + ", unreachableSince=" + snapshot.unreachableSince()
                + ", unreachableUntil=" + snapshot.lastUnreachable()
                + ", lastReachable=" + snapshot.lastReachable()
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
        // Atomic, underflow-safe decrement: a check-then-decrement could race and go negative, so clamp at 0.
        this.connections.updateAndGet(v -> Math.max(0, v - 1));
    }

    public void setWarmupPeriod(final long warmupPeriod) {
        this.warmupPeriod = warmupPeriod;
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

    /**
     * Coherent read-only view of {@link #getStatus()}, {@link #getUnreachableSince()},
     * {@link #getLastUnreachable()}, and {@link #getLastReachable()} captured atomically via {@link #snapshot()}.
     */
    public record Snapshot(Status status, long unreachableSince, long lastUnreachable, long lastReachable) {
    }

    /**
     * Immutable internal record of the four state-machine fields that transition together.
     * Held in an {@link AtomicReference} so {@code reportAs(Un)Reachable} callers cannot interleave their
     * field writes; each {@link AtomicReference#updateAndGet} call is a single linearization point.
     */
    private record State(Status status, long unreachableSince, long lastUnreachable, long lastReachable) {
        State withUnreachable(final long timestamp) {
            if (status == Status.DOWN) {
                // Already DOWN; just refresh lastUnreachable, preserve unreachableSince.
                return new State(status, unreachableSince, timestamp, lastReachable);
            }
            // Transition from COLD/STABLE to DOWN.
            return new State(Status.DOWN, timestamp, timestamp, lastReachable);
        }

        State withReachable(final long timestamp, final long warmupPeriod) {
            return switch (status) {
                case DOWN -> new State(Status.COLD, 0L, lastUnreachable, timestamp);
                case COLD -> {
                    // Warmup check uses a coherent (lastUnreachable, timestamp) pair from this snapshot,
                    // so concurrent reportAsUnreachable cannot move lastUnreachable between the two reads.
                    final Status next = (timestamp - lastUnreachable) > warmupPeriod ? Status.STABLE : Status.COLD;
                    yield new State(next, unreachableSince, lastUnreachable, timestamp);
                }
                case STABLE -> new State(Status.STABLE, unreachableSince, lastUnreachable, timestamp);
            };
        }
    }
}
