package org.carapaceproxy.server.cache;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.prometheus.client.Gauge;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.PrometheusUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheByteBufMemoryUsageMetric implements Runnable {

    public static final int DEFAULT_PERIOD = 5; // seconds
    private static final Logger LOG = LoggerFactory.getLogger(CacheByteBufMemoryUsageMetric.class);
    private static final Gauge CACHE_POOLED_BYTEBUF_ALLOCATOR = PrometheusUtils.createGauge("cacheAllocator",
            "cache_pooled_allocator_direct_memory_usage",
            "Amount of direct memory usage by cache allocator").register();
    private static final Gauge CACHE_UNPOOLED_BYTEBUF_ALLOCATOR = PrometheusUtils.createGauge("cacheAllocator",
            "cache_unpooled_allocator_direct_memory_usage",
            "Amount of direct memory usage by cache allocator").register();
    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;
    private HttpProxyServer parent;

    // can change at runtime
    private volatile int period;
    private volatile boolean started; // keep track of start() calling

    public CacheByteBufMemoryUsageMetric(HttpProxyServer parent) {
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
        LOG.info("Starting cache ByteBufAllocator usage, period: {} seconds", period);
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
        if (parent.getCachePoolAllocator() instanceof PooledByteBufAllocator) {
            CACHE_POOLED_BYTEBUF_ALLOCATOR.set(((PooledByteBufAllocator) parent.getCachePoolAllocator()).metric().usedDirectMemory());
        } else {
            CACHE_UNPOOLED_BYTEBUF_ALLOCATOR.set(((UnpooledByteBufAllocator) parent.getCachePoolAllocator()).metric().usedDirectMemory());
        }
    }
}
