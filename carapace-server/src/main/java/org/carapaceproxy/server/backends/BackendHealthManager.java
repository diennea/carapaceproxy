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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;

/**
 * Keeps status about backends
 *
 * @author enrico.olivelli
 */
public class BackendHealthManager implements Runnable {

    public static final int DEFAULT_PERIOD = 60; // seconds
    private static final Logger LOG = Logger.getLogger(BackendHealthManager.class.getName());

    private static final Gauge BACKEND_UPSTATUS_GAUGE = PrometheusUtils.createGauge("health", "backend_status",
            "backend status", "host").register();

    private EndpointMapper mapper;

    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;

    // can change at runtime
    private volatile int period;
    // can change at runtime
    private volatile int connectTimeout;
    private volatile boolean started; // keep track of start() calling

    private final ConcurrentHashMap<String, BackendHealthStatus> backends = new ConcurrentHashMap<>();

    public BackendHealthManager(RuntimeServerConfiguration conf, EndpointMapper mapper) {

        this.mapper = mapper; // may be null

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
        LOG.info("Starting BackendHealthManager, period: " + period + " seconds");
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
        int newPeriod = newConfiguration.getHealthProbePeriod();
        boolean changePeriod = period != newPeriod;
        boolean restart = scheduledFuture != null && changePeriod;

        if (restart) {
            scheduledFuture.cancel(true);
        }

        if (changePeriod) {
            period = newPeriod;
            LOG.info("Applying health probe period " + period + " s");
        }

        if (this.connectTimeout != newConfiguration.getHealthConnectTimeout()) {
            this.connectTimeout = newConfiguration.getHealthConnectTimeout();
            LOG.info("Applying new connect timeout " + this.connectTimeout + " ms");
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
            String hostPort = bconf.getHostPort();
            BackendHealthStatus status = backends.computeIfAbsent(hostPort, (_hostPort) -> new BackendHealthStatus(_hostPort));

            BackendHealthCheck checkResult = BackendHealthCheck.check(
                    bconf.host(), bconf.port(), bconf.probePath(), connectTimeout);

            if (checkResult.isOk()) {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.WARNING, "backend {0} was unreachable, setting again to reachable. Response time {1}ms",
                            new Object[]{status.getHostPort(), checkResult.getEndTs() - checkResult.getStartTs()});
                    reportBackendReachable(status.getHostPort());
                } else {
                    LOG.log(Level.FINE, "backend {0} seems reachable. Response time {1}ms",
                            new Object[]{status.getHostPort(), checkResult.getEndTs() - checkResult.getStartTs()});
                }
            } else {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.FINE, "backend {0} still unreachable. Cause: {1}", new Object[]{status.getHostPort(), checkResult.getHttpResponse()});
                } else {
                    LOG.log(Level.WARNING, "backend {0} became unreachable. Cause: {1}", new Object[]{status.getHostPort(), checkResult.getHttpResponse()});
                    reportBackendUnreachable(status.getHostPort(), checkResult.getEndTs(), checkResult.getHttpResponse());
                }
            }
            status.setLastProbe(checkResult);

            if (status.isReportedAsUnreachable()) {
                BACKEND_UPSTATUS_GAUGE.labels(bconf.host() + "_" + bconf.port()).set(0);
            } else {
                BACKEND_UPSTATUS_GAUGE.labels(bconf.host() + "_" + bconf.port()).set(1);
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String key : backends.keySet()) {
            boolean found = false;
            for (BackendConfiguration bconf : backendConfigurations) {
                if (bconf.getHostPort().equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                toRemove.add(key);
            }
        }
        if (!toRemove.isEmpty()) {
            LOG.log(Level.INFO, "discarding backends {0}", toRemove);
            toRemove.forEach(backends::remove);
        }
    }

    public void reportBackendUnreachable(String hostPort, long timestamp, String cause) {
        BackendHealthStatus backend = getBackendStatus(hostPort);
        backend.reportAsUnreachable(timestamp);
    }

    private BackendHealthStatus getBackendStatus(String hostPort) {
        BackendHealthStatus status = backends.computeIfAbsent(hostPort, (_hostPort) -> new BackendHealthStatus(_hostPort));
        if (status == null) {
            throw new RuntimeException("Unknown backend " + hostPort);
        }
        return status;
    }

    public void reportBackendReachable(String hostPort) {
        BackendHealthStatus backend = getBackendStatus(hostPort);
        backend.reportAsReachable();
    }

    public Map<String, BackendHealthStatus> getBackendsSnapshot() {
        return new HashMap<>(backends);
    }

    public boolean isAvailable(String hostPort) {
        BackendHealthStatus backend = getBackendStatus(hostPort);
        return backend != null && backend.isAvailable();
    }

    @VisibleForTesting
    public int getConnectTimeout() {
        return connectTimeout;
    }

}
