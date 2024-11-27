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

import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.Gauge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Track status about backends.
 * In conjunction with a {@link org.carapaceproxy.server.config.BackendSelector},
 * it helps {@link org.carapaceproxy.core.ProxyRequestsManager} to choose the right backend to route a request to.
 *
 * @author enrico.olivelli
 */
public class BackendHealthManager implements Runnable {

    public static final int DEFAULT_PERIOD = 60; // seconds
    private static final Logger LOG = LoggerFactory.getLogger(BackendHealthManager.class);

    private static final Gauge BACKEND_UPSTATUS_GAUGE = PrometheusUtils
            .createGauge("health", "backend_status", "backend status", "host")
            .register();

    private EndpointMapper mapper;

    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;

    // can change at runtime
    private volatile int period;
    // can change at runtime
    private volatile int connectTimeout;
    // keep track of start() calling
    private volatile boolean started;

    private final ConcurrentHashMap<EndpointKey, BackendHealthStatus> backends = new ConcurrentHashMap<>();

    public BackendHealthManager(final RuntimeServerConfiguration conf, final EndpointMapper mapper) {
        this.mapper = mapper;

        // will be overridden before start
        this.period = DEFAULT_PERIOD;
        this.connectTimeout = conf.getHealthConnectTimeout();
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public synchronized void start() {
        started = true;
        if (period <= 0) {
            return;
        }
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor();
        }
        LOG.info("Starting BackendHealthManager, period: {} seconds", period);
        scheduledFuture = timer.scheduleAtFixedRate(this, period, period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        started = false;
        if (timer != null) {
            timer.shutdown();
            try {
                timer.awaitTermination(10, TimeUnit.SECONDS);
                timer = null;
                scheduledFuture = null;
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void reloadConfiguration(RuntimeServerConfiguration newConfiguration, EndpointMapper mapper) {
        final int newPeriod = newConfiguration.getHealthProbePeriod();
        final boolean changePeriod = period != newPeriod;
        final boolean restart = scheduledFuture != null && changePeriod;

        if (restart) {
            scheduledFuture.cancel(true);
        }

        if (changePeriod) {
            period = newPeriod;
            LOG.info("Applying health probe period {} s", period);
        }

        if (this.connectTimeout != newConfiguration.getHealthConnectTimeout()) {
            this.connectTimeout = newConfiguration.getHealthConnectTimeout();
            LOG.info("Applying new connect timeout {} ms", this.connectTimeout);
        }

        this.mapper = mapper;

        if (restart || started) {
            start();
        }
    }

    @Override
    public void run() {
        if (mapper == null) {
            return;
        }
        Collection<BackendConfiguration> backendConfigurations = mapper.getBackends().values();
        for (BackendConfiguration bconf : backendConfigurations) {
            BackendHealthStatus status = backends.computeIfAbsent(bconf.hostPort(), BackendHealthStatus::new);

            BackendHealthCheck checkResult = BackendHealthCheck.check(
                    bconf.host(), bconf.port(), bconf.probePath(), connectTimeout);

            if (checkResult.isOk()) {
                final var responseTime = checkResult.endTs() - checkResult.startTs();
                if (status.isReportedAsUnreachable()) {
                    LOG.warn("backend {} was unreachable, setting again to reachable. Response time {}ms", status.getHostPort(), responseTime);
                    reportBackendReachable(status.getHostPort());
                } else {
                    LOG.debug("backend {} seems reachable. Response time {}ms", status.getHostPort(), responseTime);
                }
            } else {
                if (status.isReportedAsUnreachable()) {
                    LOG.debug("backend {} still unreachable. Cause: {}", status.getHostPort(), checkResult.httpResponse());
                } else {
                    LOG.warn("backend {} became unreachable. Cause: {}", status.getHostPort(), checkResult.httpResponse());
                    reportBackendUnreachable(status.getHostPort(), checkResult.endTs(), checkResult.httpResponse());
                }
            }
            status.setLastProbe(checkResult);

            BACKEND_UPSTATUS_GAUGE
                    .labels(bconf.host() + "_" + bconf.port())
                    .set(status.isReportedAsUnreachable() ? 0 : 1);
        }
        List<EndpointKey> toRemove = new ArrayList<>();
        for (final EndpointKey key : backends.keySet()) {
            boolean found = false;
            for (BackendConfiguration bconf : backendConfigurations) {
                if (bconf.hostPort().equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                toRemove.add(key);
            }
        }
        if (!toRemove.isEmpty()) {
            LOG.info("discarding backends {}", toRemove);
            toRemove.forEach(backends::remove);
        }
    }

    public void reportBackendUnreachable(final EndpointKey hostPort, final long timestamp, final String cause) {
        getBackendStatus(hostPort).reportAsUnreachable(timestamp, cause);
    }

    private BackendHealthStatus getBackendStatus(final EndpointKey hostPort) {
        return backends.computeIfAbsent(hostPort, BackendHealthStatus::new);
    }

    public void reportBackendReachable(final EndpointKey hostPort) {
        getBackendStatus(hostPort).reportAsReachable();
    }

    public Map<EndpointKey, BackendHealthStatus> getBackendsSnapshot() {
        return Map.copyOf(backends);
    }

    public boolean isAvailable(final EndpointKey hostPort) {
        return getBackendStatus(hostPort).isAvailable();
    }

    @VisibleForTesting
    public int getConnectTimeout() {
        return connectTimeout;
    }

}
