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
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.server.config.BackendConfiguration;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.StatsLogger;

/**
 * Keeps status about backends
 *
 * @author enrico.olivelli
 */
public class BackendHealthManager implements Runnable {

    private static final Logger LOG = Logger.getLogger(BackendHealthManager.class.getName());
    
    private final RuntimeServerConfiguration conf;
    private final EndpointMapper mapper;
    private final StatsLogger mainLogger;
    
    private ScheduledExecutorService timer;
    private int period;
    
    private final ConcurrentHashMap<String, BackendHealthStatus> backends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gauge> gauges = new ConcurrentHashMap<>();
    
    public BackendHealthManager(RuntimeServerConfiguration conf, EndpointMapper mapper, StatsLogger logger) {
        
        this.conf = conf;
        this.mapper = mapper;
        this.mainLogger = logger.scope("health");

        // will be overridden before start
        this.period = 60000;
        
        for (BackendConfiguration bconf: mapper.getBackends().values()) {
            backends.put(bconf.toBackendId(), new BackendHealthStatus(bconf.toBackendId(), bconf));
        }
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
        for (BackendHealthStatus status : backends.values()) {
            ensureGauge("backend_" + status.getId().replace(":", "_") + "_up", status);
            
            BackendHealthCheck checkResult = BackendHealthCheck.check(
                status.getConf().getHost(), status.getConf().getPort(), status.getConf().getProbePath(), conf.getConnectTimeout());
            
            if (checkResult.isOk()) {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.WARNING, "backend {0} was unreachable, setting again to reachable. Response time {1}ms", 
                        new Object[] {status.getId(), checkResult.getEndTs() - checkResult.getStartTs()});
                    reportBackendReachable(status.getId());
                } else {
                    LOG.log(Level.INFO, "backend {0} seems reachable. Response time {1}ms", 
                        new Object[] {status.getId(), checkResult.getEndTs() - checkResult.getStartTs()});
                }
                status.setLastProbeSuccess(true);
                
            } else {
                if (status.isReportedAsUnreachable()) {
                    LOG.log(Level.INFO, "backend {0} still unreachable. Cause: {1}", new Object[] {status.getId(), checkResult.getResultStr()});
                } else {
                    LOG.log(Level.WARNING, "backend {0} became unreachable. Cause: {1}", new Object[] {status.getId(), checkResult.getResultStr()});
                    reportBackendUnreachable(status.getId(), checkResult.getEndTs(), checkResult.getResultStr());
                }
                status.setLastProbeSuccess(false);
            }
            
            status.setLastProbeResult(checkResult.getResultStr());
            status.setLastProbeTs(checkResult.getEndTs());
        }
    }

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

    public void reportBackendUnreachable(String id, long timestamp, String cause) {
        BackendHealthStatus backend = getBackendStatus(id);
        backend.reportAsUnreachable(timestamp);
    }

    private BackendHealthStatus getBackendStatus(String id) {
        BackendHealthStatus backend = backends.get(id);
        if (backend == null) {
            throw new RuntimeException("Unknown backend "+id);
        }
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
