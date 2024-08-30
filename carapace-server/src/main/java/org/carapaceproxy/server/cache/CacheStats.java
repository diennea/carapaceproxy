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
package org.carapaceproxy.server.cache;

import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.carapaceproxy.utils.PrometheusUtils;

/**
 * Overall statistics about cache
 */
public class CacheStats {

    private static final Counter HITS_COUNTER = PrometheusUtils.createCounter("cache", "hits_total", "cache hits count").register();
    private static final Counter MISSES_COUNTER = PrometheusUtils.createCounter("cache", "misses_total", "cache misses count").register();
    private static final Gauge PAYLOAD_MEMORY_USED_GAUGE = PrometheusUtils.createGauge("cache", "payload_memory_usage_bytes", "memory currently used", "area").register();
    private static final Gauge TOTAL_MEMORY_USED_GAUGE = PrometheusUtils.createGauge("cache", "total_memory_usage_bytes", "memory currently used").register();

    private final Gauge.Child directMemoryUsed;
    private final Gauge.Child heapMemoryUsed;

    public CacheStats() {
        directMemoryUsed = PAYLOAD_MEMORY_USED_GAUGE.labels("direct");
        heapMemoryUsed = PAYLOAD_MEMORY_USED_GAUGE.labels("heap");
    }

    public void update(boolean hit) {
        if (hit) {
            HITS_COUNTER.inc();
        } else {
            MISSES_COUNTER.inc();
        }
    }

    public void cached(long heap, long direct, long total) {
        directMemoryUsed.inc(direct);
        heapMemoryUsed.inc(heap);
        TOTAL_MEMORY_USED_GAUGE.inc(total);
    }

    public void released(long heap, long direct, long total) {
        directMemoryUsed.dec(direct);
        heapMemoryUsed.dec(heap);
        TOTAL_MEMORY_USED_GAUGE.dec(total);
    }

    public long getDirectMemoryUsed() {
        return (long) directMemoryUsed.get();
    }

    public long getHeapMemoryUsed() {
        return (long) heapMemoryUsed.get();
    }

    public long getTotalMemoryUsed() {
        return (long) TOTAL_MEMORY_USED_GAUGE.get();
    }

    public long getHits() {
        return (long) HITS_COUNTER.get();
    }

    public long getMisses() {
        return (long) MISSES_COUNTER.get();
    }
    
    /**
     * Resets to 0 all cache metrics. This should only be used for testing purposes
     */
    @VisibleForTesting
    public void resetCacheMetrics() {
        TOTAL_MEMORY_USED_GAUGE.set(0);
        directMemoryUsed.set(0);
        heapMemoryUsed.set(0);
        
        HITS_COUNTER.clear();
        MISSES_COUNTER.clear();
    }

}
