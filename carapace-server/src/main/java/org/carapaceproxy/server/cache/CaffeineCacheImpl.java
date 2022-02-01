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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import static com.github.benmanes.caffeine.cache.RemovalCause.COLLECTED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import com.github.benmanes.caffeine.cache.RemovalListener;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.server.cache.ContentsCache.ContentKey;
import org.carapaceproxy.server.cache.ContentsCache.CachedContent;

/**
 *
 * @author francesco.caliumi
 */
class CaffeineCacheImpl implements CacheImpl {

    private static final int INITIAL_CACHE_SIZE_CAPACITY = 2000;

    private final Cache<ContentKey, CachedContent> cache;
    private final CacheStats stats;
    private Logger logger;

    private AtomicLong entries = new AtomicLong(0);
    private AtomicLong memSize = new AtomicLong(0);

    private boolean verbose = false;
    private volatile RemovalListener removalListener;

    public CaffeineCacheImpl(CacheStats stats, long cacheMaxSize, Logger logger) {
        this.stats = stats;
        this.logger = logger;

        this.cache = Caffeine.<ContentKey, CachedContent>newBuilder()
            .initialCapacity((int) INITIAL_CACHE_SIZE_CAPACITY)
            .maximumWeight(cacheMaxSize > 0 ? cacheMaxSize : Long.MAX_VALUE)
            .expireAfter(new Expiry<ContentKey, CachedContent>() {
                @Override
                public long expireAfterCreate(ContentKey key, CachedContent payload, long currentTime) {
                    // WARNING: provided current time is completely misleading, as stated in the doc.
                    // System.currentTimeMillis() should be used instead.
                    return (payload.expiresTs - System.currentTimeMillis()) * 1_000_000; // In nanos
                }
                @Override
                public long expireAfterUpdate(ContentKey key, CachedContent payload, long currentTime, long currentDuration) {
                    return currentDuration;
                }
                @Override
                public long expireAfterRead(ContentKey key, CachedContent payload, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .weigher((ContentKey key, CachedContent payload) -> {
                    return (int) (key.getMemUsage() + payload.getMemUsage());
                }
            )
            .removalListener((ContentKey key, CachedContent payload, RemovalCause cause) -> {
                switch (cause) {
                    case COLLECTED:
                        throw new IllegalStateException("cant be collected");
                    case EXPIRED:
                        if (verbose) {
                            logger.log(Level.FINE, "content {0}: expired at {1}", new Object[]{key.uri, new java.util.Date(payload.expiresTs)});
                        }
                        break;
                    case EXPLICIT:
                        if (verbose) {
                            logger.log(Level.FINE, "content {0}: explicitly removed", new Object[]{key.uri});
                        }
                        break;
                    case REPLACED:
                        if (verbose) {
                            logger.log(Level.FINE, "content {0}: replaced", new Object[]{key.uri});
                        }
                        break;
                    case SIZE:
                        if (verbose) {
                            logger.log(Level.FINE, "content {0}: removed due to max size exceeded", new Object[]{key.uri});
                        }
                        break;
                }
                if (removalListener != null) {
                    removalListener.onRemoval(key, payload, cause);
                }
                release(key, payload);
            })
            .build();
    }

    void setRemovalListener(RemovalListener listener) {
        this.removalListener = listener;
    }

    @Override
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public int getSize() {
        return entries.intValue();
    }

    @Override
    public long getMemSize() {
        return memSize.get();
    }

    @Override
    public void put(ContentKey key, CachedContent payload) {
        cache.put(key, payload);

        stats.cached(payload.heapSize, payload.directSize, key.getMemUsage() + payload.getMemUsage());
        entries.addAndGet(1);
        memSize.addAndGet(key.getMemUsage() + payload.getMemUsage());
        
        logger.log(Level.FINE, "adding content {0}", key.uri);
    }

    @Override
    public CachedContent get(ContentKey key) {
        CachedContent cached = cache.getIfPresent(key);
        if (cached != null && cached.expiresTs < System.currentTimeMillis()) {
            logger.log(Level.FINE, "expiring content {0}, expired at {1}", new Object[]{key.uri, new java.util.Date(cached.expiresTs)});
            cache.invalidate(key);
            cached = null;
        }
        stats.update(cached != null);
        if (cached != null) {
            cached.hits++;
        }
        return cached;
    }

    private void release(ContentKey key, CachedContent payload) {
        stats.released(payload.heapSize, payload.directSize, key.getMemUsage() + payload.getMemUsage());
        entries.addAndGet(-1);
        memSize.addAndGet(-(key.getMemUsage() + payload.getMemUsage()));
        payload.clear();
    }

    @Override
    public void remove(ContentKey key) {
        cache.invalidate(key);
    }

    @Override
    public void evict() {
        cache.cleanUp();
    }

    @Override
    public int clear() {
        int currentSize = getSize();
        cache.invalidateAll();
        cache.cleanUp();
        return currentSize;
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public void inspectCache(CacheEntriesSink sink) {
        cache.asMap().forEach((key, payload) -> {
            sink.accept(key, payload);
        });
    }
}
