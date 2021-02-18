/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server.cache;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import static io.netty.handler.codec.http.HttpStatusClass.REDIRECTION;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
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
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;

/**
 * Keeps contents in cache
 */
public class ContentsCache {

    private static final Logger LOG = Logger.getLogger(ContentsCache.class.getName());

    private static final Counter NO_CACHE_REQUESTS_COUNTER = PrometheusUtils.createCounter("cache", "non_cachable_requests_total", "not cachable requests").register();

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

        this.currentConfiguration = new CacheRuntimeConfiguration(currentConfiguration.getCacheMaxSize(), currentConfiguration.getCacheMaxFileSize());

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

    private boolean isContentLengthCachable(long contentLength) {
        if (currentConfiguration.getCacheMaxFileSize() > 0
                && contentLength > currentConfiguration.getCacheMaxFileSize()) {
            return false;
        } else {
            return true;
        }
    }

    private boolean isContentLengthCachable(HttpHeaders headers) {
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

    private boolean isCachable(HttpResponse response) {
        HttpHeaders headers = response.headers();
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL);
        if ((cacheControl != null && CACHE_CONTROL_CACHE_DISABLED_VALUES.stream().anyMatch(v -> cacheControl.replaceAll(" ", "").toLowerCase().contains(v)))
                || headers.contains(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE, true)
                || !isContentLengthCachable(headers)) {
            // never cache Pragma: no-cache, Cache-Control: nostore/no-cache
            LOG.log(Level.FINER, "not cachable {0}", response);
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

    private boolean isCachable(HttpRequest request, boolean registerNoCacheStat) {
        switch (request.method().name()) {
            case "GET":
            case "HEAD":
                // got haed and cache
                break;
            default:
                return false;
        }
        boolean ctrlF5 = request.headers()
                .containsValue(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE, true);
        if (ctrlF5) {
            if (registerNoCacheStat) {
                NO_CACHE_REQUESTS_COUNTER.inc();
            }
            return false;
        }
        String uri = request.uri();
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

    public ContentReceiver startCachingResponse(HttpRequest request) {
        if (!isCachable(request, true)) {
            return null;
        }
        return new ContentReceiver(new ContentKey(request));

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
                newConfiguration.getCacheMaxSize(), newConfiguration.getCacheMaxFileSize());
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
        private final ContentPayload cached;

        private ContentSender(ContentKey key, ContentPayload cached) {
            this.key = key;
            this.cached = cached;
        }

        public ContentKey getKey() {
            return key;
        }

        public ContentPayload getCached() {
            return cached;
        }

    }

    public ContentSender serveFromCache(RequestHandler handler) {
        if (!isCachable(handler.getRequest(), false)) {
            return null;
        }
        ContentKey key = new ContentKey(handler.getRequest());
        ContentPayload cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        return new ContentSender(key, cached);

    }

    public static class ContentPayload {

        final List<HttpObject> chunks = new ArrayList<>();
        final long creationTs = System.currentTimeMillis();
        long lastModified;
        long expiresTs = -1;
        long heapSize;
        long directSize;
        int hits;

        @Override
        public String toString() {
            return "ContentPayload{" + "chunks_n=" + chunks.size() + ", creationTs=" + new java.sql.Timestamp(creationTs) + ", lastModified=" + new java.sql.Timestamp(lastModified) + ", expiresTs=" + new java.sql.Timestamp(
                    expiresTs) + ", size=" + (heapSize + directSize) + " (heap=" + heapSize + ", direct=" + directSize + ")" + '}';
        }

        public long getLastModified() {
            return lastModified;
        }

        public long getExpiresTs() {
            return expiresTs;
        }

        public long getCreationTs() {
            return creationTs;
        }

        public long getHeapSize() {
            return heapSize;
        }

        public long getDirectSize() {
            return directSize;
        }

        public int getHits() {
            return hits;
        }

        public long getMemUsage() {
            // Just an estimate
            return chunks.size() * 8
                    + directSize + heapSize
                    + 8 * 5
                    + // other fields
                    4 * 1;
        }

        public List<HttpObject> getChunks() {
            return chunks;
        }

        void clear() {
            for (HttpObject o : chunks) {
                ReferenceCountUtil.release(o);
            }
            chunks.clear();
        }

        private void addChunk(HttpObject msg) {
            chunks.add(msg);
            heapSize += getHttpObjectHeapSize(msg);
            directSize += getHttpObjectDirectSize(msg);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 41 * hash + Objects.hashCode(this.chunks);
            hash = 41 * hash + (int) (this.creationTs ^ (this.creationTs >>> 32));
            hash = 41 * hash + (int) (this.lastModified ^ (this.lastModified >>> 32));
            hash = 41 * hash + (int) (this.expiresTs ^ (this.expiresTs >>> 32));
            hash = 41 * hash + (int) (this.heapSize ^ (this.heapSize >>> 32));
            hash = 41 * hash + (int) (this.directSize ^ (this.directSize >>> 32));
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
            final ContentPayload other = (ContentPayload) obj;
            if (this.creationTs != other.creationTs) {
                return false;
            }
            if (this.lastModified != other.lastModified) {
                return false;
            }
            if (this.expiresTs != other.expiresTs) {
                return false;
            }
            if (this.heapSize != other.heapSize) {
                return false;
            }
            if (this.directSize != other.directSize) {
                return false;
            }
            if (!Objects.equals(this.chunks, other.chunks)) {
                return false;
            }
            return true;
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

        ContentKey(String method, String host, String uri) {
            this.method = method;
            this.host = host;
            this.uri = uri;
        }

        public ContentKey(HttpRequest request) {
            this.method = request.method().name();
            this.host = request.headers().getAsString(HttpHeaderNames.HOST);
            this.uri = request.uri();
        }

        public long getMemUsage() {
            // Just an estimate
            return sizeof(method)
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

        public String composeKey() {
            return method + " | " + host + " | " + uri;
        }

        @Override
        public String toString() {
            return "ContentKey{" + "method=" + method + ", host=" + host + ", uri=" + uri + '}';
        }

        @Override
        public int hashCode() {
            int hash = 3;
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

    private void cacheContent(ContentReceiver receiver) {
        ContentPayload content = receiver.content;
        // Now we have the actual content size
        if (!isContentLengthCachable(content.heapSize + content.directSize)) {
            cache.remove(receiver.key); // just for make sure
            return;
        }
        cache.put(receiver.key, content);
    }

    public class ContentReceiver {

        private final ContentKey key;
        private final ContentPayload content;
        private boolean notReallyCachable = false;

        public ContentReceiver(ContentKey key) {
            this.key = key;
            this.content = new ContentPayload();
        }

        public void abort() {
            LOG.log(Level.FINEST, "Aborting cache receiver for {0}", key);
            content.clear();
        }

        public void receivedFromRemote(HttpObject msg) {
            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                if (!isCachable(response)) {
                    notReallyCachable = true;
                }
                long expiresTs = response.headers().getTimeMillis(HttpHeaderNames.EXPIRES, -1);
                if (expiresTs == -1) {
                    expiresTs = computeDefaultExpireDate();
                } else if (expiresTs < System.currentTimeMillis()) {
                    // already expired ?
                    notReallyCachable = true;
                }
                content.expiresTs = expiresTs;
                long lastModified = response.headers().getTimeMillis(HttpHeaderNames.LAST_MODIFIED, -1);
                content.lastModified = lastModified;
            }
            if (notReallyCachable) {
                LOG.log(Level.FINEST, "{0} rejecting non-cachable response", key);
                abort();
                return;
            }
            msg = cloneHttpObject(msg);
//            LOG.info(key + " accepting chunk " + msg);

            content.addChunk(msg);
            if (msg instanceof LastHttpContent) {
                cacheContent(this);
            }
        }
    }

    public static long getHttpObjectHeapSize(HttpObject msg) {
        if (msg instanceof HttpResponse) {
            return 0;
        } else if (msg instanceof DefaultHttpContent) {
            DefaultHttpContent df = (DefaultHttpContent) msg;
            ByteBuf content = df.content();
            if (content.isDirect()) {
                return 0;
            } else {
                return content.capacity();
            }
        } else if (msg instanceof LastHttpContent) {
            // EmptyLastHttpContent
            return 0;
        } else {
            throw new IllegalStateException("cannot estimate HttpObject " + msg);
        }
    }

    public static long getHttpObjectDirectSize(HttpObject msg) {
        if (msg instanceof HttpResponse) {
            return 0;
        } else if (msg instanceof DefaultHttpContent) {
            DefaultHttpContent df = (DefaultHttpContent) msg;
            ByteBuf content = df.content();
            if (content.isDirect()) {
                return content.capacity();
            } else {
                return 0;
            }
        } else if (msg instanceof LastHttpContent) {
            // EmptyLastHttpContent
            return 0;
        } else {
            throw new IllegalStateException("cannot estimate HttpObject " + msg);
        }
    }

    public static HttpObject cloneHttpObject(HttpObject msg) {
        if (msg instanceof FullHttpResponse) {
            FullHttpResponse fr = (FullHttpResponse) msg;
            return fr.copy();
        } else if (msg instanceof DefaultHttpResponse) {
            DefaultHttpResponse fr = (DefaultHttpResponse) msg;
            return new DefaultHttpResponse(fr.protocolVersion(), fr.status(), fr.headers());
        } else if (msg instanceof DefaultLastHttpContent) {
            DefaultLastHttpContent df = (DefaultLastHttpContent) msg;
            return df.copy();
        } else if (msg instanceof DefaultHttpContent) {
            DefaultHttpContent df = (DefaultHttpContent) msg;
            return df.copy();
        } else if (msg instanceof LastHttpContent) {
            return ((LastHttpContent) msg).copy();
        } else {
            LOG.severe("cannot duplicate HttpObject " + msg);
            throw new IllegalStateException("cannot duplicate HttpObject " + msg);
        }
    }

    private class Evictor implements Runnable {

        @Override
        public void run() {
            cache.evict();
        }
    }
}
