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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.StatsLogger;
import org.carapaceproxy.EndpointMapper;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;

/**
 * Keeps status about backends
 *
 * @author enrico.olivelli
 */
public class BackendHealthManager implements Runnable {

    private static final Logger LOG = Logger.getLogger(BackendHealthManager.class.getName());

    private EndpointMapper mapper;
    private final StatsLogger mainLogger;

    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;

    // can change at runtime
    private volatile AtomicInteger period = new AtomicInteger(0);
    // can change at runtime
    private volatile AtomicInteger connectTimeout = new AtomicInteger(60000);

    private final ConcurrentHashMap<String, BackendHealthStatus> backends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gauge> gauges = new ConcurrentHashMap<>();

    public BackendHealthManager(RuntimeServerConfiguration conf, EndpointMapper mapper, StatsLogger logger) {

        this.mapper = mapper; // may be null
        this.mainLogger = logger.scope("health");

        // will be overridden before start
        this.period.set(60000);
        this.connectTimeout.set(conf.getConnectTimeout());

    }

    public int getPeriod() {
        return period.get();
    }

    public void setPeriod(int period) {
        this.period.set(period);
    }

    public synchronized void start() {
        int _period = period.get();
        if (_period <= 0) {
            return;
        }
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor();
        }
        LOG.info("Starting BackendHealthManager, period: " + _period + " seconds");
        scheduledFuture = timer.scheduleAtFixedRate(this, _period, _period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
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

        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }

        if (this.connectTimeout.get() != newConfiguration.getConnectTimeout()) {
            this.connectTimeout.set(newConfiguration.getConnectTimeout());
            LOG.info("Applying new connect timeout " + this.connectTimeout + " ms");
        }

        this.mapper = mapper;

        this.period.set(newPeriod);
        LOG.info("Applying health probe period " + newPeriod + " s");

        if (scheduledFuture != null) {
            start();
        }
    }

    private void ensureGauge(String key, BackendHealthStatus status) {
        gauges.computeIfAbsent(key, (k) -> {
            Gauge gauge = new Gauge() {
                @Override
                public Number getDefaultValue() {
                    return 0;
                }

                @Override
                public Number getSample() {
                    return status.isReportedAsUnreachable() ? 0 : 1;
                }
            };
            mainLogger.registerGauge(k, gauge);
            return gauge;
        });
    }

    @Override
    public void run() {
        if (mapper == null) {
            return;
        }
        Collection<BackendConfiguration> backendConfigurations = mapper.getBackends().values();
        for (BackendConfiguration bconf : backendConfigurations) {
            String backendId = bconf.getHostPort();
            BackendHealthStatus status = backends.computeIfAbsent(backendId, (id) -> new BackendHealthStatus(id));
            ensureGauge("backend_" + status.getId().replace(":", "_") + "_up", status);

            BackendHealthCheck checkResult = BackendHealthCheck.check(
                    bconf.getHost(), bconf.getPort(), bconf.getProbePath(), connectTimeout.get());

            if (checkResult.isOk()) {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.WARNING, "backend {0} was unreachable, setting again to reachable. Response time {1}ms",
                            new Object[]{status.getId(), checkResult.getEndTs() - checkResult.getStartTs()});
                    reportBackendReachable(status.getId());
                } else {
                    LOG.log(Level.INFO, "backend {0} seems reachable. Response time {1}ms",
                            new Object[]{status.getId(), checkResult.getEndTs() - checkResult.getStartTs()});
                }
                status.setLastProbeSuccess(true);

            } else {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.INFO, "backend {0} still unreachable. Cause: {1}", new Object[]{status.getId(), checkResult.getResultStr()});
                } else {
                    LOG.log(Level.WARNING, "backend {0} became unreachable. Cause: {1}", new Object[]{status.getId(), checkResult.getResultStr()});
                    reportBackendUnreachable(status.getId(), checkResult.getEndTs(), checkResult.getResultStr());
                }
                status.setLastProbeSuccess(false);
            }

            status.setLastProbeResult(checkResult.getResultStr());
            status.setLastProbeTs(checkResult.getEndTs());
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

    public void reportBackendUnreachable(String id, long timestamp, String cause) {
        BackendHealthStatus backend = getBackendStatus(id);
        backend.reportAsUnreachable(timestamp);
    }

    private BackendHealthStatus getBackendStatus(String backendId) {
        BackendHealthStatus status = backends.computeIfAbsent(backendId, (id) -> new BackendHealthStatus(id));
        if (status == null) {
            throw new RuntimeException("Unknown backend " + backendId);
        }
        return status;
    }

    public void reportBackendReachable(String id) {
        BackendHealthStatus backend = getBackendStatus(id);
        backend.reportAsReachable();
    }

    public Map<String, BackendHealthStatus> getBackendsSnapshot() {
        return new HashMap<>(backends);
    }

    public boolean isAvailable(String id) {
        BackendHealthStatus backend = getBackendStatus(id);
        return backend != null && backend.isAvailable();
    }

    @VisibleForTesting
    public int getConnectTimeout() {
        return connectTimeout.get();
    }

}
