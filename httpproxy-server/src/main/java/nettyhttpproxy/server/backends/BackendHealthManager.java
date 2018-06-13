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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps status about backends
 *
 * @author enrico.olivelli
 */
public class BackendHealthManager implements Runnable {

    private ConcurrentHashMap<String, BackendHealthStatus> backends
            = new ConcurrentHashMap<>();

    private ScheduledExecutorService timer;
    private int period;

    public BackendHealthManager() {
        // will be overridden before start
        this.period = 60000;
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public void start() {
        if (period <= 0) {
            return;
        }
        LOG.info("Starting BackendHealthManager, period: " + period + " seconds");
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(this, period, period, TimeUnit.SECONDS);
    }

    @Override
    public void run() {        
        for (BackendHealthStatus status : backends.values()) {
            if (status.isReportedAsUnreachable()) {
                // TODO: perform a probe
                LOG.log(Level.INFO, "backend {0} was unreachable, setting again to reachable (probe not really performed!!)", status.getId());
                status.reportAsReachable();
                status.setLastProbeResult("MOCK OK");
                status.setLastProbeSuccess(true);
                status.setLastProbeTs(System.currentTimeMillis());
            } else {
                LOG.log(Level.INFO, "backend {0} seems reachable", status.getId());
            }
        }
    }
    private static final Logger LOG = Logger.getLogger(BackendHealthManager.class.getName());

    public void stop() {
        if (timer != null) {
            timer.shutdown();
            try {
                timer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void reportBackendUnreachable(String id, long timestamp) {
        BackendHealthStatus backend = getBackendStatus(id);
        backend.reportAsUnreachable(timestamp);
    }

    private BackendHealthStatus getBackendStatus(String id) {
        BackendHealthStatus backend = backends.computeIfAbsent(id, BackendHealthStatus::new);
        return backend;
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

}
