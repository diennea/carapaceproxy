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
package nettyhttpproxy.server.cache;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import static io.netty.handler.codec.http.HttpStatusClass.REDIRECTION;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import nettyhttpproxy.server.RequestHandler;

/**
 * Keeps contents in cache
 */
public class ContentsCache {

    private static final Logger LOG = Logger.getLogger(ContentsCache.class.getName());

    private final ConcurrentMap<ContentKey, ContentPayload> cache = new ConcurrentHashMap<>();
    private final CacheStats stats = new CacheStats();

    public void close() {
        cache.values().forEach(ContentPayload::clear);
        cache.clear();
    }

    private static boolean isCachable(HttpResponse response) {
        String pragma = response.headers().get(HttpHeaderNames.PRAGMA);
        if (pragma != null && pragma.contains("no-cache")) {
            // never cache Pragma: no-cache
            LOG.info("not cachable " + response);
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

    private static boolean isCachable(HttpRequest request) {
        if (!request.method().name().equals("GET")) {
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
                    return true;
                default:
                    return false;
            }
        }
        return true;

    }

    public ContentReceiver startCachingResponse(HttpRequest request) {
        if (!isCachable(request)) {
            return null;
        }
        String uri = request.uri();
        return new ContentReceiver(new ContentKey(uri));

    }

    public class ContentSender {

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
        if (!isCachable(handler.getRequest())) {
            return null;
        }
        String uri = handler.getRequest().uri();
        ContentKey key = new ContentKey(uri);
        ContentPayload cached = cache.get(key);
        stats.update(cached != null);
        if (cached == null) {
            return null;
        }
        return new ContentSender(key, cached);

    }

    public static class ContentPayload {

        private final List<HttpObject> chunks = new ArrayList<>();
        private long creationTs = System.currentTimeMillis();

        public long getCreationTs() {
            return creationTs;
        }

        public List<HttpObject> getChunks() {
            return chunks;
        }

        private void clear() {
            for (HttpObject o : chunks) {
                ReferenceCountUtil.release(o);
            }
            chunks.clear();
        }
    }

    public static class ContentKey {

        private final String uri;

        public ContentKey(String uri) {
            this.uri = uri;
        }

        public String getUri() {
            return uri;
        }

        @Override
        public String toString() {
            return "ContentKey{" + "uri=" + uri + '}';
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 61 * hash + Objects.hashCode(this.uri);
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
            if (!Objects.equals(this.uri, other.uri)) {
                return false;
            }
            return true;
        }

    }

    public int getCacheSize() {
        return cache.size();
    }

    public CacheStats getStats() {
        return stats;
    }

    private void cacheContent(ContentReceiver receiver) {
        LOG.info("Caching content " + receiver.key);
        cache.put(receiver.key, receiver.content);

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
            LOG.info("Aborting cache receiver for " + key);
            content.clear();
        }

        public void receivedFromRemote(HttpObject msg) {
            if (msg instanceof HttpResponse) {
                if (!isCachable((HttpResponse) msg)) {
                    notReallyCachable = true;
                }
            }
            if (notReallyCachable) {
                LOG.info(key + " rejecting non-cachable response");
                abort();
                return;
            }
            LOG.info(key + " accepting chunk " + msg);
            ReferenceCountUtil.retain(msg);
            content.chunks.add(msg);
            if (msg instanceof LastHttpContent) {
                cacheContent(this);
            }
        }

    }
}
