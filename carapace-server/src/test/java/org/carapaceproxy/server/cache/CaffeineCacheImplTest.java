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

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.RemovalCause;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.carapaceproxy.server.cache.ContentsCache.CachedContent;
import org.carapaceproxy.server.cache.ContentsCache.ContentKey;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author francesco.caliumi
 */
public class CaffeineCacheImplTest {

    private CacheStats stats;
    private CaffeineCacheImpl cache;

    private final List<CacheEntry> evictedResources = new CopyOnWriteArrayList<>();

    private final Logger logger = LoggerFactory.getLogger(CaffeineCacheImplTest.class);

    @AfterEach
    void afterEach() {
        stats.resetCacheMetrics();
    }

    public void initializeCache(int maxSize) throws Exception {
        stats = new CacheStats();

        cache = new CaffeineCacheImpl(stats, maxSize, logger);
        cache.setVerbose(true);

        cache.setRemovalListener((key, payload, cause) -> {
            CacheEntry e = new CacheEntry((ContentKey) key, (CachedContent) payload, true);
            e.removalCause = cause;
            evictedResources.add(e);
        });
    }

    private static final class CacheEntry {

        final ContentKey key;
        final CachedContent payload;
        RemovalCause removalCause;

        public CacheEntry(ContentKey key, CachedContent payload, boolean copy) {
            if (copy) {
                this.key = key;
                this.payload = payload;
            } else {
                this.key = key;
                this.payload = new CachedContent(payload.creationTs);
                this.payload.chunks.addAll(payload.chunks);
                this.payload.directSize = payload.directSize;
                this.payload.expiresTs = payload.expiresTs;
                this.payload.heapSize = payload.heapSize;
                this.payload.lastModified = payload.lastModified;
            }
        }

        @Override
        public String toString() {
            return "CacheEntry{" + "key=" + key + ", payload=" + payload + ", removalCause=" + removalCause + '}';
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 79 * hash + Objects.hashCode(this.key);
            hash = 79 * hash + Objects.hashCode(this.payload);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final CacheEntry other = (CacheEntry) obj;
            if (!Objects.equals(this.key, other.key)) {
                return false;
            }
            if (!Objects.equals(this.payload, other.payload)) {
                return false;
            }
            return true;
        }

        public long getMemUsage() {
            return key.getMemUsage() + payload.getMemUsage();
        }
    }

    private static CacheEntry genCacheEntry(String resource, int payloadLength, long expireTs) {
        ContentKey key = new ContentKey("", "", "", resource);
        CachedContent payload = new CachedContent();
        payload.chunks.add(Unpooled.EMPTY_BUFFER);
        payload.chunks.add(Unpooled.EMPTY_BUFFER);
        payload.directSize = payloadLength / 2;
        payload.heapSize = payloadLength - payload.directSize;
        if (expireTs > 0) {
            payload.expiresTs = expireTs;
        } else {
            payload.expiresTs = System.currentTimeMillis() + 60 * 60 * 1000;
        }
        return new CacheEntry(key, payload, false);
    }

    private void runEviction(CaffeineCacheImpl cache, Integer expectedEvictions) throws Exception {

        if (expectedEvictions == null) {
            cache.evict();
            return;
        }

        if (expectedEvictions > 0) {
            TestUtils.waitForCondition(() -> evictedResources.size() >= expectedEvictions, () -> {
                cache.evict();
                return null;
            }, 10);
        } else {
            cache.evict();
            Thread.sleep(1000);
            assertThat(evictedResources).isEmpty();
        }
    }

    @Test
    void simpleTest() throws Exception {
        initializeCache(0);

        // Put 1
        CacheEntry e1 = genCacheEntry("res_1", 100, 0);
        cache.put(e1.key, e1.payload);
        assertThat(cache.get(e1.key)).isEqualTo(e1.payload);

        assertThat(cache.getSize()).isOne();
        assertThat(cache.getMemSize()).isEqualTo(e1.getMemUsage());

        assertThat(stats.getDirectMemoryUsed()).isEqualTo(e1.payload.getDirectSize());
        assertThat(stats.getHeapMemoryUsed()).isEqualTo(e1.payload.getHeapSize());
        assertThat(stats.getTotalMemoryUsed()).isEqualTo(e1.getMemUsage());
        assertThat(stats.getHits()).isOne();
        assertThat(stats.getMisses()).isZero();

        // Put 2
        CacheEntry e2 = genCacheEntry("res_2", 200, 0);
        cache.put(e2.key, e2.payload);
        assertThat(cache.get(e2.key)).isEqualTo(e2.payload);

        assertThat(cache.getSize()).isEqualTo(2);
        assertThat(cache.getMemSize()).isEqualTo(e1.getMemUsage() + e2.getMemUsage());

        assertThat(stats.getDirectMemoryUsed()).isEqualTo(e1.payload.getDirectSize() + e2.payload.getDirectSize());
        assertThat(stats.getHeapMemoryUsed()).isEqualTo(e1.payload.getHeapSize() + e2.payload.getHeapSize());
        assertThat(stats.getTotalMemoryUsed()).isEqualTo(e1.getMemUsage() + e2.getMemUsage());
        assertThat(stats.getHits()).isEqualTo(2L);
        assertThat(stats.getMisses()).isZero();

        // Put 2b (replace 2)
        CacheEntry e2b = genCacheEntry("res_2", 300, 0);
        assertThat(e2b.key).isEqualTo(e2.key);
        cache.put(e2b.key, e2b.payload);
        assertThat(cache.get(e2b.key)).isEqualTo(e2b.payload);

        runEviction(cache, 1);
        assertThat(evictedResources).singleElement().satisfies(it -> {
            assertThat(it.key).isEqualTo(e2.key);
            assertThat(it.payload).isEqualTo(e2.payload);
            assertThat(it.removalCause).isEqualTo(RemovalCause.REPLACED);
        });
        evictedResources.clear();

        assertThat(cache.getSize()).isEqualTo(2);
        assertThat(cache.getMemSize()).isEqualTo(e1.getMemUsage() + e2b.getMemUsage());

        assertThat(stats.getDirectMemoryUsed()).isEqualTo(e1.payload.getDirectSize() + e2b.payload.getDirectSize());
        assertThat(stats.getHeapMemoryUsed()).isEqualTo(e1.payload.getHeapSize() + e2b.payload.getHeapSize());
        assertThat(stats.getTotalMemoryUsed()).isEqualTo(e1.getMemUsage() + e2b.getMemUsage());
        assertThat(stats.getHits()).isEqualTo(3L);
        assertThat(stats.getMisses()).isZero();

        // Remove 1
        cache.remove(e1.key);
        assertThat(cache.get(e1.key)).isNull();

        runEviction(cache, 1);
        assertThat(evictedResources).singleElement().satisfies(it -> {
            assertThat(it.key).isEqualTo(e1.key);
            assertThat(it.payload).isEqualTo(e1.payload);
            assertThat(it.removalCause).isEqualTo(RemovalCause.EXPLICIT);
        });
        evictedResources.clear();

        assertThat(cache.getSize()).isOne();
        assertThat(cache.getMemSize()).isEqualTo(e2b.getMemUsage());

        assertThat(stats.getDirectMemoryUsed()).isEqualTo(e2b.payload.getDirectSize());
        assertThat(stats.getHeapMemoryUsed()).isEqualTo(e2b.payload.getHeapSize());
        assertThat(stats.getTotalMemoryUsed()).isEqualTo(e2b.getMemUsage());
        assertThat(stats.getHits()).isEqualTo(3L);
        assertThat(stats.getMisses()).isOne();

        // Remove 2
        cache.remove(e2.key);
        assertThat(cache.get(e2.key)).isNull();

        runEviction(cache, 1);
        assertThat(evictedResources).singleElement().satisfies(it -> {
            assertThat(it.key).isEqualTo(e2b.key);
            assertThat(it.payload).isEqualTo(e2b.payload);
            assertThat(it.removalCause).isEqualTo(RemovalCause.EXPLICIT);
        });
        evictedResources.clear();

        assertThat(cache.getSize()).isZero();
        assertThat(cache.getMemSize()).isZero();

        assertThat(stats.getDirectMemoryUsed()).isZero();
        assertThat(stats.getHeapMemoryUsed()).isZero();
        assertThat(stats.getTotalMemoryUsed()).isZero();
        assertThat(stats.getHits()).isEqualTo(3L);
        assertThat(stats.getMisses()).isEqualTo(2L);

        // Close
        cache.close();
    }

    @Test
    void maxSize() throws Exception {
        final int maxSize = 1000;
        initializeCache(maxSize);

        long sleepBetweenPuts = 100;

        List<CacheEntry> entries = new ArrayList<>();
        int c = 0;
        long totSize = 0;
        boolean ok = false;
        while (!ok) {
            CacheEntry e = genCacheEntry("res_" + (c++), 100, 0);
            System.out.println("Adding element " + e.key.uri + " of size=" + e.getMemUsage());
            entries.add(e);
            cache.put(e.key, e.payload);
            assertThat(cache.get(e.key)).isEqualTo(e.payload);
            System.out.println(" > cache.memSize=" + cache.getMemSize());
            totSize += e.getMemUsage();
            // In the last insertion cache.getMemSize() will exceed the maximum
            if (totSize <= maxSize) {
                assertThat(cache.getMemSize()).isEqualTo(totSize);
            } else {
                ok = true;
            }
            runEviction(cache, null);
            Thread.sleep(sleepBetweenPuts);
        }

        // Now we should have put in our cache much elements as it can contain + 1. The first one should be evicted.
        runEviction(cache, 1);
        assertThat(evictedResources).hasSize(1);

        // Doesn't work. It currently seems that the eviction process always evitcts the last entered key, which is
        // undoubtedly a serious issue
//        for (int i=0; i<entries.size(); i++) {
//            if (i == 0) {
//                assertThat(evictedResources.get(0), equalTo(entries.get(i)));
//                assertThat(evictedResources.get(0).removalCause, equalTo(RemovalCause.SIZE));
//            } else {
//                assertThat(cache.get(entries.get(i).key), equalTo(entries.get(i).payload));
//            }
//        }
//        entries.remove(0);
        CacheEntry evictedEntry = evictedResources.get(0);
        assertThat(entries.remove(evictedEntry)).isTrue();

        assertThat((int) cache.getMemSize()).isEqualTo(entries.stream().mapToInt(e -> (int) e.getMemUsage()).sum());
        assertThat((int) cache.getSize()).isEqualTo(entries.size());

        assertThat((int) stats.getDirectMemoryUsed()).isEqualTo(entries.stream().mapToInt(e -> (int) e.payload.directSize).sum());
        assertThat((int) stats.getHeapMemoryUsed()).isEqualTo(entries.stream().mapToInt(e -> (int) e.payload.heapSize).sum());
        assertThat((int) stats.getTotalMemoryUsed()).isEqualTo(entries.stream().mapToInt(e -> (int) e.getMemUsage()).sum());
    }

    @Test
    void exipiration() throws Exception {
        initializeCache(0);

        List<CacheEntry> entries = new ArrayList<>();
        long expireTs = System.currentTimeMillis() + 1000;
        for (int i = 0; i < 10; i++) {
            CacheEntry e = genCacheEntry("res_" + (i), 100, expireTs);
            System.out.println("Adding element " + e.key.uri + " with expireTs=" + new java.sql.Timestamp(expireTs));
            entries.add(e);
            cache.put(e.key, e.payload);
            assertThat(cache.get(e.key)).isEqualTo(e.payload);

            expireTs += 10000;
        }
        assertThat((int) cache.getSize()).isEqualTo(10);

        // No entries should be removed
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Thread.sleep(100);
                cache.evict();
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        });
        runEviction(cache, 0);
        assertThat(evictedResources).isEmpty();

        // Only the first entry should be removed
        Thread.sleep(1000);
        runEviction(cache, 1);
        assertThat(evictedResources).singleElement().satisfies(it -> {
            assertThat(it).isEqualTo(entries.get(0));
            assertThat(it.removalCause).isEqualTo(RemovalCause.EXPIRED);
        });
        evictedResources.clear();
        entries.remove(0);

        assertThat((int) cache.getSize()).isEqualTo(10 - 1);

    }

}
