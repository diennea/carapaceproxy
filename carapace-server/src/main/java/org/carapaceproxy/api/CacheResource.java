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
package org.carapaceproxy.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.cache.CacheStats;
import org.carapaceproxy.server.cache.ContentsCache;

/**
 * Access to proxy cache
 *
 * @author enrico.olivelli
 */
@Path("/cache")
@Produces("application/json")
public class CacheResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    @Path("/flush")
    @GET
    public Map<String, Object> flush() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        ContentsCache cache = server.getCache();
        int size = cache.clear();
        Map<String, Object> res = new HashMap<>();
        res.put("result", "ok");
        res.put("cachesize", size);
        return res;
    }

    @Path("/info")
    @GET
    public Map<String, Object> info() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        ContentsCache cache = server.getCache();
        int size = cache.getCacheSize();
        CacheStats stats = cache.getStats();
        Map<String, Object> res = new HashMap<>();
        res.put("result", "ok");
        res.put("cachesize", size);
        res.put("hits", stats.getHits());
        res.put("misses", stats.getMisses());
        res.put("directMemoryUsed", stats.getDirectMemoryUsed());
        res.put("heapMemoryUsed", stats.getHeapMemoryUsed());
        res.put("totalMemoryUsed", stats.getTotalMemoryUsed());
        return res;
    }
    
    @Path("/inspect")
    @GET
    public List<Map<String, Object>> inspect() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        ContentsCache cache = server.getCache();
        return cache.inspectCache();
    }
}
