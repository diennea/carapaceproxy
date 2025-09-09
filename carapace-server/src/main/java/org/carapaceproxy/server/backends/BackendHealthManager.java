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
import java.io.File;
import java.security.KeyStore;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.mapper.EndpointMapper;
import org.carapaceproxy.utils.CertificatesUtils;
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
    private final ConcurrentHashMap<EndpointKey, BackendHealthStatus> backends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SSLSocketFactory> httpsFactoryCache = new ConcurrentHashMap<>();
    private final File basePath;
    private EndpointMapper mapper;
    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;
    // can change at runtime
    private volatile int period;
    // can change at runtime
    private volatile int connectTimeout;
    // keep track of start() calling
    private volatile boolean started;
    private volatile long warmupPeriod;
    private volatile boolean tolerant;

    public BackendHealthManager(final RuntimeServerConfiguration conf, final EndpointMapper mapper) {
        this(conf, mapper, new File("."));
    }

    public BackendHealthManager(final RuntimeServerConfiguration conf, final EndpointMapper mapper, final File basePath) {
        this.mapper = mapper;
        this.connectTimeout = conf.getHealthConnectTimeout();
        this.warmupPeriod = conf.getWarmupPeriod();
        this.tolerant = conf.isTolerant();
        this.basePath = basePath != null ? basePath.getAbsoluteFile() : new File(".");

        // will be overridden before start
        this.period = DEFAULT_PERIOD;
    }

    public boolean isTolerant() {
        return tolerant;
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
        this.httpsFactoryCache.clear();
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

        if (this.warmupPeriod != newConfiguration.getWarmupPeriod()) {
            this.warmupPeriod = newConfiguration.getWarmupPeriod();
            this.backends.values().forEach(it -> it.setWarmupPeriod(warmupPeriod));
            LOG.info("Applying new warmup period of {} ms", this.warmupPeriod);
        }

        if (this.tolerant != newConfiguration.isTolerant()) {
            this.tolerant = newConfiguration.isTolerant();
            LOG.info("Applying new health tolerance configuration {}; cold backends now {} exceed safe capacity", this.tolerant, this.tolerant ? "may" : "may not");
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
        for (final BackendConfiguration backend : mapper.getBackends().values()) {
            final EndpointKey endpoint = backend.hostPort();
            final BackendHealthStatus status = getBackendStatus(endpoint);

            SSLSocketFactory httpsFactory = null;
            if ("https".equalsIgnoreCase(backend.probeScheme())) {
                final String caPath = backend.caCertificatePath();
                if (caPath != null && !caPath.isBlank()) {
                    httpsFactory = getOrCreateHttpsFactory(backend);
                }
            }

            final BackendHealthCheck checkResult = BackendHealthCheck.check(backend, connectTimeout, httpsFactory);

            if (checkResult.ok()) {
                switch (status.getStatus()) {
                    case DOWN ->
                            LOG.warn("backend {} was unreachable, setting again to reachable. Response time {} ms", endpoint, checkResult.responseTime());
                    case COLD, STABLE ->
                            LOG.debug("backend {} seems reachable. Response time {} ms", endpoint, checkResult.responseTime());
                }
                reportBackendReachable(endpoint, checkResult.endTs());
            } else {
                switch (status.getStatus()) {
                    case DOWN ->
                            LOG.debug("backend {} still unreachable. Cause: {}", endpoint, checkResult.httpResponse());
                    case COLD, STABLE ->
                            LOG.warn("backend {} became unreachable. Cause: {}", endpoint, checkResult.httpResponse());
                }
                reportBackendUnreachable(endpoint, checkResult.endTs(), checkResult.httpResponse());
            }
            status.setLastProbe(checkResult);

            BACKEND_UPSTATUS_GAUGE
                    .labels(backend.host() + "_" + backend.port())
                    .set(status.getStatus() == BackendHealthStatus.Status.DOWN ? 0 : 1);
        }
        cleanup();
    }

    private SSLSocketFactory getOrCreateHttpsFactory(final BackendConfiguration bconf) {
        final String caPath = bconf.caCertificatePath();
        final String pwd = bconf.caCertificatePassword() != null ? bconf.caCertificatePassword() : "";
        if (caPath == null || caPath.isBlank()) {
            return null;
        }
        final File caFile = caPath.startsWith("/") ? new File(caPath) : new File(basePath, caPath);
        final String key = caFile.getAbsoluteFile().toString();
        return httpsFactoryCache.computeIfAbsent(key, k -> {
            try {
                final KeyStore trustStore = CertificatesUtils.loadKeyStoreFromFile(caPath, pwd, basePath);
                if (trustStore == null) {
                    return null;
                }
                final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                final SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tmf.getTrustManagers(), null);
                return ctx.getSocketFactory();
            } catch (Exception e) {
                LOG.warn("Unable to build HTTPS SSLSocketFactory for backend {}: {}", bconf.id(), e.toString());
                return null;
            }
        });
    }

    private void cleanup() {
        if (mapper == null) {
            return;
        }
        backends.keySet().retainAll(mapper.getBackends().values().stream().map(BackendConfiguration::hostPort).toList());
    }

    public void reportBackendReachable(final EndpointKey hostPort, final long timestamp) {
        getBackendStatus(hostPort).reportAsReachable(timestamp);
    }

    public void reportBackendUnreachable(final EndpointKey hostPort, final long timestamp, final String cause) {
        getBackendStatus(hostPort).reportAsUnreachable(timestamp, cause);
    }

    public Map<EndpointKey, BackendHealthStatus> getBackendsSnapshot() {
        return Map.copyOf(backends);
    }

    public BackendHealthStatus getBackendStatus(final EndpointKey hostPort) {
        return backends.computeIfAbsent(hostPort, key -> new BackendHealthStatus(key, warmupPeriod));
    }

    public BackendHealthStatus getBackendStatus(final String backendId) {
        final BackendConfiguration backendConfiguration = this.mapper.getBackends().get(backendId);
        Objects.requireNonNull(backendConfiguration);
        return getBackendStatus(backendConfiguration.hostPort());
    }

    public boolean exceedsCapacity(final String backendId) {
        final BackendConfiguration backendConfiguration = this.mapper.getBackends().get(backendId);
        Objects.requireNonNull(backendConfiguration);
        if (backendConfiguration.safeCapacity() <= 0) {
            return false;
        }
        final BackendHealthStatus backendStatus = getBackendStatus(backendConfiguration.hostPort());
        return backendConfiguration.safeCapacity() > backendStatus.getConnections();
    }

    @VisibleForTesting
    public int getConnectTimeout() {
        return connectTimeout;
    }

}
