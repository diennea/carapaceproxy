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

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import nettyhttpproxy.server.ClientConnectionHandler;
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

    private static boolean isCachable(HttpRequest request) {
        return request.method().name().equals("GET");
    }

    public ContentReceiver startCachingResponse(HttpRequest request) {
        if (!isCachable(request)) {
            return null;
        }
        String uri = request.uri();
        return new ContentReceiver(new ContentKey(uri, System.currentTimeMillis()));
    }

    public boolean serveFromCache(RequestHandler handler) {
        if (!isCachable(handler.getRequest())) {
            return false;
        }
        String uri = handler.getRequest().uri();
        ContentKey key = new ContentKey(uri, 0);
        ContentPayload cached = cache.get(key);
        stats.update(cached != null);
        if (cached == null) {
            return false;
        }
        handler.serveFromCache(cached);
        return true;
    }

    public static class ContentPayload {

        private final List<HttpObject> chunks = new ArrayList<>();

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
        private final long creationTs;

        public ContentKey(String uri, long creationTs) {
            this.uri = uri;
            this.creationTs = creationTs;
        }

        public String getUri() {
            return uri;
        }

        public long getCreationTs() {
            return creationTs;
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

        public ContentReceiver(ContentKey key) {
            this.key = key;
            this.content = new ContentPayload();
        }

        public void abort() {
            LOG.info("Aborting cache receiver for " + key);
            content.clear();
        }

        public void receivedFromRemote(HttpObject msg) {
            LOG.info(key + " accepting chunk " + msg);
            ReferenceCountUtil.retain(msg);
            content.chunks.add(msg);
            if (msg instanceof LastHttpContent) {
                cacheContent(this);
            }
        }

    }
}
