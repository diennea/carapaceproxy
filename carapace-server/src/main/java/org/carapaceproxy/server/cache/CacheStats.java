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

import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.StatsLogger;

/**
 * Overall statistics about cache
 */
public class CacheStats {

    private final Counter hits;
    private final Counter misses;
    private final Counter directMemoryUsed;
    private final Counter heapMemoryUsed;
    private final Counter totalMemoryUsed;

    public CacheStats(StatsLogger cacheScope) {
        this.hits = cacheScope.getCounter("hits");
        this.misses = cacheScope.getCounter("misses");
        this.directMemoryUsed = cacheScope.getCounter("directMemoryUsed");
        this.heapMemoryUsed = cacheScope.getCounter("heapMemoryUsed");
        this.totalMemoryUsed = cacheScope.getCounter("totalMemoryUsed");
    }

    public void update(boolean hit) {
        if (hit) {
            hits.inc();
        } else {
            misses.inc();
        }
    }

    public void cached(long heap, long direct, long total) {
        directMemoryUsed.add(direct);
        heapMemoryUsed.add(heap);
        totalMemoryUsed.add(total);
    }

    public void released(long heap, long direct, long total) {
        directMemoryUsed.add(-direct);
        heapMemoryUsed.add(-heap);
        totalMemoryUsed.add(-total);
    }

    public long getDirectMemoryUsed() {
        return directMemoryUsed.get();
    }

    public long getHeapMemoryUsed() {
        return heapMemoryUsed.get();
    }

    public long getTotalMemoryUsed() {
        return totalMemoryUsed.get();
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

}
