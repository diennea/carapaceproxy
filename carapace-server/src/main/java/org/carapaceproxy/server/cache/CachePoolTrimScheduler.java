package org.carapaceproxy.server.cache;

import io.netty.channel.EventLoop;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.utils.CarapaceLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class CachePoolTrimScheduler implements Runnable {

    public static final int DEFAULT_CACHE_POOL_TRIM_INTERVAL = 3600; // seconds
    private static final Logger LOG = Logger.getLogger(CachePoolTrimScheduler.class.getName());

    private ScheduledExecutorService timer;
    private ScheduledFuture<?> scheduledFuture;
    private HttpProxyServer parent;

    // can change at runtime
    private volatile int period;
    private volatile boolean started; // keep track of start() calling

    public CachePoolTrimScheduler(HttpProxyServer parent) {
        this.period = DEFAULT_CACHE_POOL_TRIM_INTERVAL;
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
        LOG.info("Starting cache pool trim scheduler, period: " + period + " seconds");
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


    public synchronized void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        int newPeriod = newConfiguration.getCachePoolTrimInterval();
        boolean changePeriod = period != newPeriod;
        boolean restart = scheduledFuture != null && changePeriod;

        if (restart) {
            scheduledFuture.cancel(true);
        }

        if (changePeriod) {
            period = newPeriod;
            LOG.info("Applying new cache trim interval " + period + " s");
        }

        if (restart || started) {
            start();
        }
    }

    @Override
    public void run() {
        final EventLoop loop = parent.getEventLoopGroup().next();
        loop.execute(() -> {
            if (parent.getCachePoolAllocator().trimCurrentThreadCache()) {
                if (CarapaceLogger.isLoggingDebugEnabled()) {
                    CarapaceLogger.debug("Cache PooledByteBufAllocator: Trim thread {0} cache pool",
                            Thread.currentThread().getName());
                }
            }
        });
    }
}
