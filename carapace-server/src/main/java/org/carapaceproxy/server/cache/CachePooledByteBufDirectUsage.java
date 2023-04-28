package org.carapaceproxy.server.cache;

import io.prometheus.client.Gauge;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.CarapaceLogger;
import org.carapaceproxy.utils.PrometheusUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CachePooledByteBufDirectUsage implements Runnable {

    public static final int DEFAULT_PERIOD = 5; // seconds
    private static final Logger LOG = Logger.getLogger(CachePooledByteBufDirectUsage.class.getName());


    private static final Gauge CACHE_ALLOCATOR_DIRECT_MEMORY_USAGE = PrometheusUtils.createGauge(
            "cacheAllocator", "cache_allocator_direct_memory_usage", "Amount of direct memory usage by cache allocator"
    ).register();


    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;
    private HttpProxyServer parent;

    // can change at runtime
    private volatile int period;
    private volatile boolean started; // keep track of start() calling

    public CachePooledByteBufDirectUsage(HttpProxyServer parent) {
        this.period = DEFAULT_PERIOD;
        this.parent = parent;
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
        LOG.info("Starting cache pooledByteBufAllocator usage, period: " + period + " seconds");
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


    @Override
    public void run() {
        if(CarapaceLogger.isLoggingDebugEnabled()) {
            CarapaceLogger.debug("cache allocator status: " + parent.getCacheAllocator().metric().toString());
        }
        CACHE_ALLOCATOR_DIRECT_MEMORY_USAGE.set(parent.getCacheAllocator().metric().usedDirectMemory());
    }
}
