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
package org.carapaceproxy.cluster.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone implementation: no peers and no events, locks are local to the JVM.
 *
 * @author eolivelli
 */
public class NullGroupMembershipHandler implements GroupMembershipHandler {

    private static final Logger LOG = LoggerFactory.getLogger(NullGroupMembershipHandler.class);

    private Map<String, String> peerInfo = Map.of("address", "localhost");
    private final String peerId = "local";
    private final ConcurrentHashMap<String, ReentrantLock> mutexes = new ConcurrentHashMap<>();
    private final ReentrantLock storeWriteLock = new ReentrantLock();

    @Override
    public void start() {
    }

    @Override
    public void watchEvent(String eventId, EventCallback callback) {
        // nothing to do, 'cause self events have to be ignored.
    }

    @Override
    public void fireEvent(String eventId, Map<String, Object> data) {
        // nothing to do, 'cause self events have to be ignored.
    }

    @Override
    public List<String> getPeers() {
        return Collections.emptyList();
    }

    @Override
    public String describePeer(String id) {
       if (peerId.equals(id)) {
            return peerId;
        }
        return null;
    }

    @Override
    public void stop() {
    }

    @Override
    public void executeInMutex(String lockId, int timeout, Runnable runnable) {
        final ReentrantLock mutex = mutexes.computeIfAbsent(lockId, id -> new ReentrantLock());
        try {
            if (!mutex.tryLock(timeout, TimeUnit.SECONDS)) {
                LOG.info("Failed to acquire lock for executeInMutex (mutexId: {})", lockId);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Interrupted while waiting for lock {}", lockId);
            return;
        }
        try {
            runnable.run();
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public StoreWriteLock acquireStoreWriteLock(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        if (!storeWriteLock.tryLock(timeout, unit)) {
            throw new TimeoutException("Store write lock not acquired within " + timeout + " " + unit);
        }
        return new StoreWriteLock() {
            @Override
            public void ensureHeld() {
                if (!storeWriteLock.isHeldByCurrentThread()) {
                    throw new IllegalStateException("Store write lock not held by the current thread");
                }
            }

            @Override
            public void close() {
                storeWriteLock.unlock();
            }
        };
    }

    @Override
    public String getLocalPeer() {
        return peerId;
    }

    @Override
    public void storeLocalPeerInfo(Map<String, String> info) {
        peerInfo = new HashMap(info);
    }

    @Override
    public Map<String, String> loadInfoForPeer(String id) {
        if (peerId.equals(id)) {
            return peerInfo;
        }
        return null;
    }

}
