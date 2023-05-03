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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.prometheus.client.Counter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import lombok.Data;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Keeps contents in cache
 */
public class ContentsCache {

    private static final Logger LOG = Logger.getLogger(ContentsCache.class.getName());

    private static final Counter NO_CACHE_REQUESTS_COUNTER = PrometheusUtils.createCounter("cache", "non_cacheable_requests_total", "not cacheable requests").register();

    public static final List<String> CACHE_CONTROL_CACHE_DISABLED_VALUES = Arrays.asList(
            HttpHeaderValues.PRIVATE + "",
            HttpHeaderValues.NO_CACHE + "",
            HttpHeaderValues.NO_STORE + "",
            HttpHeaderValues.MAX_AGE + "=0"
    );

    private CacheImpl cache;

    private final CacheStats stats;
    private final ScheduledExecutorService threadPool;
    private CacheRuntimeConfiguration currentConfiguration;

    static final long DEFAULT_TTL = 1000 * 60 * 60;

    public ContentsCache(RuntimeServerConfiguration currentConfiguration) {
        this.stats = new CacheStats();
        this.threadPool = Executors.newSingleThreadScheduledExecutor();

        this.currentConfiguration = new CacheRuntimeConfiguration(
                currentConfiguration.getCacheMaxSize(),
                currentConfiguration.getCacheMaxFileSize(),
                currentConfiguration.isCacheDisabledForSecureRequestsWithoutPublic()
        );
        this.cache = new CaffeineCacheImpl(stats, currentConfiguration.getCacheMaxSize(), LOG);
    }

    public void start() {
        this.threadPool.scheduleWithFixedDelay(new Evictor(), 1, 1, TimeUnit.MINUTES);
    }

    public void close() {
        this.threadPool.shutdownNow();
        try {
            this.threadPool.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException exit) {
            Thread.currentThread().interrupt();
        }
        this.cache.close();
    }

    private boolean isContentLengthCacheable(long contentLength) {
        return !(currentConfiguration.getCacheMaxFileSize() > 0
                && contentLength > currentConfiguration.getCacheMaxFileSize());
    }

    private boolean isContentLengthCacheable(HttpHeaders headers) {
        if (currentConfiguration.getCacheMaxFileSize() <= 0) {
            return true;
        }
        try {
            long contentLength = Integer.parseInt(headers.get(HttpHeaderNames.CONTENT_LENGTH, "-1"));
            if (contentLength > 0) {
                return contentLength <= currentConfiguration.getCacheMaxFileSize();
            } else {
                return true;
            }
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    public boolean isCacheable(HttpClientResponse response) {
        HttpHeaders headers = response.responseHeaders();
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL, "").replaceAll(" ", "").toLowerCase();
        if ((!cacheControl.isEmpty() && CACHE_CONTROL_CACHE_DISABLED_VALUES.stream().anyMatch(v -> cacheControl.contains(v)))
                || headers.contains(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE, true)
                || !isContentLengthCacheable(headers)) {
            // never cache Pragma: no-cache, Cache-Control: nostore/no-cache
            LOG.log(Level.FINER, "not cacheable {0}", response);
            return false;
        }
        switch (response.status().codeClass()) {
            case SUCCESS:
                return true;
            case REDIRECTION:
            case INFORMATIONAL:
            case SERVER_ERROR:
            case UNKNOWN:
            default:
                return false;
        }

    }

    private boolean isCacheable(ProxyRequest request, boolean registerNoCacheStat) {
        switch (request.getMethod().name()) {
            case "GET":
            case "HEAD":
                // got haed and cache
                break;
            default:
                return false;
        }

        final HttpHeaders headers = request.getRequestHeaders();
        final String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL, "").replaceAll(" ", "").toLowerCase();
        boolean ctrlF5 = cacheControl.contains(HttpHeaderValues.NO_CACHE + "");
        if (ctrlF5) {
            if (registerNoCacheStat) {
                NO_CACHE_REQUESTS_COUNTER.inc();
            }
            return false;
        }
        if (this.currentConfiguration.isCacheDisabledForSecureRequestsWithoutPublic()
                && request.isSecure() && !cacheControl.contains(HttpHeaderValues.PUBLIC + "")) {
            return false;
        }
        if ((!cacheControl.isEmpty() && CACHE_CONTROL_CACHE_DISABLED_VALUES.stream().anyMatch(v -> cacheControl.contains(v)))
                || headers.contains(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE, true)) {
            // never cache Pragma: no-cache, Cache-Control: nostore/no-cache
            return false;
        }

        String uri = request.getUri();
        String queryString = "";
        int question = uri.indexOf('?');
        if (question > 0) {
            queryString = uri.substring(question + 1);
            uri = uri.substring(0, question);
        }
        if (!queryString.isEmpty()) {
            // never cache data with a query string, unless it is an image or a script or css
            int dot = uri.lastIndexOf('.');
            String extension = "";
            if (dot >= 0) {
                extension = uri.substring(dot + 1);
            }
            switch (extension) {
                case "png":
                case "gif":
                case "jpg":
                case "jpeg":
                case "js":
                case "css":
                case "woff2":
                    return true;
                default:
                    return false;
            }
        }

        return true;
    }

    @VisibleForTesting
    void runEvictor() {
        new Evictor().run();
    }

    public ContentReceiver createCacheReceiver(ProxyRequest request) {
        return isCacheable(request, true) ? new ContentReceiver(new ContentKey(request)) : null;
    }

    public final long computeDefaultExpireDate() {
        return System.currentTimeMillis() + DEFAULT_TTL;
    }

    public int clear() {
        LOG.info("clearing cache");
        return this.cache.clear();
    }

    public List<Map<String, Object>> inspectCache() {
        List<Map<String, Object>> res = new ArrayList<>();
        this.cache.inspectCache((key, payload) -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("method", key.method);
            entry.put("host", key.host);
            entry.put("scheme", key.scheme);
            entry.put("uri", key.uri);
            entry.put("cacheKey", key.composeKey());
            entry.put("heapSize", payload.heapSize);
            entry.put("directSize", payload.directSize);
            entry.put("totalSize", key.getMemUsage() + payload.getMemUsage());
            entry.put("creationTs", payload.creationTs);
            entry.put("expiresTs", payload.expiresTs);
            entry.put("hits", payload.hits);
            res.add(entry);
        });
        return res;
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        CacheRuntimeConfiguration newCacheConfiguration = new CacheRuntimeConfiguration(
                newConfiguration.getCacheMaxSize(),
                newConfiguration.getCacheMaxFileSize(),
                newConfiguration.isCacheDisabledForSecureRequestsWithoutPublic()
        );
        if (newCacheConfiguration.equals(currentConfiguration)) {
            LOG.info("Cache configuration not changed during hot reload");
            return;
        }

        LOG.info("Cache configuration changed during hot reload, flushing");
        // need to clear
        CacheImpl oldCache = this.cache;
        this.cache = new CaffeineCacheImpl(stats, newCacheConfiguration.getCacheMaxSize(), LOG);
        currentConfiguration = newCacheConfiguration;
        oldCache.clear();
    }

    public static final class ContentSender {

        private final ContentKey key;
        private final CachedContent cached;

        private ContentSender(ContentKey key, CachedContent cached) {
            this.key = key;
            this.cached = cached;
        }

        public ContentKey getKey() {
            return key;
        }

        public CachedContent getCached() {
            return cached;
        }

    }

    public ContentSender getCacheSender(ProxyRequest request) {
        if (!isCacheable(request, false)) {
            return null;
        }

        ContentKey key = new ContentKey(request);
        CachedContent cached = cache.get(key);

        return cached != null ? new ContentSender(key, cached) : null;
    }

    @Data
    public static class CachedContent {

        HttpClientResponse response;
        final List<ByteBuf> chunks = new ArrayList<>();
        final long creationTs = System.currentTimeMillis();
        long lastModified;
        long expiresTs = -1;
        long heapSize;
        long directSize;
        int hits;

        private synchronized void addChunk(ByteBuf chunk, ByteBufAllocator allocator) {
            ByteBuf originalChunk = chunk.retainedDuplicate();
            ByteBuf directBuffer = allocator.directBuffer(originalChunk.readableBytes());
            directBuffer.writeBytes(originalChunk);
            chunks.add(directBuffer);
            if (directBuffer.isDirect()) {
                directSize += directBuffer.capacity();
            } else {
                heapSize += directBuffer.capacity();
            }
            originalChunk.release();
        }

        synchronized void clear() {
            chunks.forEach(ByteBuf::release);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "ContentsCache refCnt after release");
                chunks.forEach(buff -> LOG.log(Level.FINE, "refCnt: {0}", buff.refCnt()));
            }
            chunks.clear();
        }

        public List<ByteBuf> getChunks() {
            return chunks.stream()
                    .map(c -> c.retainedDuplicate())
                    .collect(Collectors.toList());
        }

        public long getMemUsage() {
            // Just an estimate
            return chunks.size() * 8
                    + directSize + heapSize
                    + 8 * 5
                    + // other fields
                    4 * 1;
        }

        @Override
        public String toString() {
            return "ContentPayload{" + "chunks_n=" + chunks.size() + ", creationTs=" + new java.sql.Timestamp(creationTs) + ", lastModified=" + new java.sql.Timestamp(lastModified) + ", expiresTs=" + new java.sql.Timestamp(
                    expiresTs) + ", size=" + (heapSize + directSize) + " (heap=" + heapSize + ", direct=" + directSize + ")" + '}';
        }

    }

    private static long sizeof(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof String) {
            return 8
                    + // object header used by the VM
                    8
                    + // 64-bit reference to char array (value)
                    8 + ((String) o).length() * 2
                    + // character array itself (object header + 16-bit chars)
                    4
                    + // offset integer
                    4
                    + // count integer
                    4; // cached hash code
        }
        throw new IllegalArgumentException("Unknown object " + o.getClass());
    }

    public static class ContentKey {

        final String method;
        final String host;
        final String uri;
        final String scheme;

        ContentKey(String method, String scheme, String host, String uri) {
            this.method = method;
            this.host = host;
            this.uri = uri;
            this.scheme = scheme;
        }

        public ContentKey(ProxyRequest request) {
            this.method = request.getMethod().name();
            this.host = request.getRequestHeaders().getAsString(HttpHeaderNames.HOST);
            this.uri = request.getUri();
            this.scheme = request.getScheme();
        }

        public long getMemUsage() {
            // Just an estimate
            return  sizeof(scheme)
                    + sizeof(method)
                    + sizeof(host)
                    + sizeof(uri);
        }

        public String getMethod() {
            return method;
        }

        public String getHost() {
            return host;
        }

        public String getUri() {
            return uri;
        }

        public String getScheme() {
            return scheme;
        }

        public String composeKey() {
            return scheme + " | " + method + " | " + host + " | " + uri;
        }

        @Override
        public String toString() {
            return "ContentKey{" + "scheme=" + scheme + ", method=" + method + ", host=" + host + ", uri=" + uri + '}';
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 83 * hash + Objects.hashCode(this.scheme);
            hash = 83 * hash + Objects.hashCode(this.method);
            hash = 83 * hash + Objects.hashCode(this.host);
            hash = 83 * hash + Objects.hashCode(this.uri);
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
            final ContentKey other = (ContentKey) obj;
            if (!Objects.equals(this.method, other.method)) {
                return false;
            }
            if (!Objects.equals(this.host, other.host)) {
                return false;
            }
            if (!Objects.equals(this.uri, other.uri)) {
                return false;
            }
            if (!Objects.equals(this.scheme, other.scheme)) {
                return false;
            }

            return true;
        }
    }

    @VisibleForTesting
    CacheImpl getInnerCache() {
        return this.cache;
    }

    public int getCacheSize() {
        return (int) cache.getSize();
    }

    public long getCacheMemSize() {
        return cache.getMemSize();
    }

    public CacheStats getStats() {
        return stats;
    }

    public void cacheContent(ContentReceiver receiver) {
        if (receiver == null) {
            return;
        }
        CachedContent content = receiver.content;
        // Now we have the actual content size
        if (!isContentLengthCacheable(content.heapSize + content.directSize)) {
            cache.remove(receiver.key); // just for make sure
            return;
        }
        cache.put(receiver.key, content);
    }

    public class ContentReceiver {

        private final ContentKey key;
        private final CachedContent content;
        private boolean notReallyCacheable = false;

        public ContentReceiver(ContentKey key) {
            this.key = key;
            this.content = new CachedContent();
        }

        public void abort() {
            LOG.log(Level.FINEST, "Aborting cache receiver for {0}", key);
            content.clear();
        }

        public boolean receivedFromRemote(HttpClientResponse response) {
            if (!isCacheable(response)) {
                notReallyCacheable = true;
            }
            long expiresTs = response.responseHeaders().getTimeMillis(HttpHeaderNames.EXPIRES, -1);
            if (expiresTs == -1) {
                expiresTs = computeDefaultExpireDate();
            } else if (expiresTs < System.currentTimeMillis()) {
                // already expired ?
                notReallyCacheable = true;
            }
            content.expiresTs = expiresTs;
            long lastModified = response.responseHeaders().getTimeMillis(HttpHeaderNames.LAST_MODIFIED, -1);
            content.lastModified = lastModified;
            if (notReallyCacheable) {
                LOG.log(Level.FINEST, "{0} rejecting non-cacheable response", key);
                abort();
                return false;
            }
            content.setResponse(response);

            return true;
        }

        public void receivedFromRemote(ByteBuf chunk, ByteBufAllocator allocator) {
            if (notReallyCacheable) {
                LOG.log(Level.FINEST, "{0} rejecting non-cacheable response", key);
                abort();
                return;
            }
            content.addChunk(chunk, allocator);
        }
    }

    private class Evictor implements Runnable {

        @Override
        public void run() {
            cache.evict();
        }
    }
}
