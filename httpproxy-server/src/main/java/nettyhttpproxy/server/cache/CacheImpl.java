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

import nettyhttpproxy.server.cache.ContentsCache.ContentKey;
import nettyhttpproxy.server.cache.ContentsCache.ContentPayload;

/**
 *
 * @author francesco.caliumi
 */
interface CacheImpl {

    /**
     * Sets cache to verbose
     * @param verbose 
     */
    public void setVerbose(boolean verbose);
    
    /**
     * Gets the esteemed number entries in cache
     * @return
     */
    public int getSize();

    /**
     * Gets the esteemed memory consumption of cache contents
     * @return 
     */
    public long getMemSize();

    /**
     * Adds an element to cache and updates the stats
     * @param key
     * @param payload 
     */
    public void put(ContentKey key, ContentPayload payload);

    /**
     * Gets an element from cache if presents and updates the stats
     * @param key
     * @return Cached element or null if key was not found in cache
     */
    public ContentPayload get(ContentKey key);

    /**
     * Removes an element from cache, frees its resources and updates the stats
     * 
     * NOTE: removal will eventually erase key and payload, so if the cached element has been previously got from the
     * cache ti will no longer be usable after calling this method
     * 
     * @param key
     * @return The removed element
     */
    public void remove(ContentKey key);
    
    /**
     * Tries to force eviction on cache
     */
    public void evict();
    
    /**
     * Removes all elements from cache
     * @return Esteemed number of elements removed
     */
    public int clear();

    /**
     * Clears the cache and free all its resources
     */
    public void close();
    
    interface CacheEntriesSink {
        public void accept(ContentKey key, ContentPayload payload);
    } 
    
    /**
     * Calls "sink" for every current element in cache
     * @param sink 
     */
    public void inspectCache(CacheEntriesSink sink);
    
}
