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
package org.carapaceproxy.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handle group membership: peer discovery and configuration changes.
 *
 * @author eolivelli
 */
public interface GroupMembershipHandler {

    /**
     * Start the service locally and notify on the network
     *
     * @param peerID
     */
    void start();

    /**
     * Unregister from the network, events won't be notified anymore.
     */
    void stop();

    /**
     * Register a callback to be called when a particular event is fired.
     *
     * @param eventId
     * @param callback
     */
    void watchEvent(String eventId, EventCallback callback);

    /**
     * Notify an event on the group.
     *
     * @param eventId
     * @param data
     */
    void fireEvent(String eventId, Map<String, Object> data);

    /**
     * To execute code in mutual exclusion to other peers.
     *
     * Whether the peer fails to acquire/release the mutex code passed will be skipped and no exceptions will be thrown.
     *
     * @param lockId
     * @param runnable
     */
    void executeInMutex(String lockId, int acquireTimeout, Runnable runnable);

    /**
     * Acquire the lock that serialises every write to the shared configuration store across the cluster.
     * Unlike {@link #executeInMutex(String, int, Runnable)} it fails explicitly.
     * The lock is reentrant for the calling thread; release it with {@link StoreWriteLock#close()} on the same thread.
     *
     * @param timeout how long to wait for the lock
     * @param unit    the unit of {@code timeout}
     * @return the held lock
     * @throws TimeoutException     if the lock is not acquired within the timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    StoreWriteLock acquireStoreWriteLock(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException;

    /**
     *
     * @return id of the local peer.
     */
    String getLocalPeer();

    /**
     * List current peers
     *
     * @return
     */
    List<String> getPeers();

    /**
     * Textual description of a Peer
     *
     * @param peerId
     * @return a string desribing the peer.
     */
    String describePeer(String peerId);

    /**
     * To store some key-value info for the local peer.
     *
     * @param info properties of the peer.
     */
    void storeLocalPeerInfo(Map<String, String> info);

    /**
     * To load info associated to the peer.
     *
     * @param id
     * @return properties of the peer
     */
    Map<String, String> loadInfoForPeer(String id);

    /**
     * A held store write lock, see {@link #acquireStoreWriteLock(long, TimeUnit)}.
     */
    interface StoreWriteLock extends AutoCloseable {

        /**
         * Check that the lock is still held by the current thread, for instance right before a commit.
         *
         * @throws IllegalStateException if the lock can no longer be trusted (e.g. the ZooKeeper session was lost)
         */
        void ensureHeld();

        /**
         * Release the lock. Store failures, like a lost ZooKeeper session, are logged, never thrown.
         */
        @Override
        void close();
    }

    interface EventCallback {

        /**
         * Called whenever an event is fired. This method should not access external resources and it must not be expensive. Inside this method you cannot call other methods of the same
         * {@link GroupMembershipHandler}.
         *
         * @param eventId
         * @param data
         */
        void eventFired(String eventId, Map<String, Object> data);

        /**
         * Called whenever ZK connection has been re-established.
         */
        void reconnected();
    }
}
