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

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

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
     */
    void fireEvent(String eventId);

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
     *
     * @return id of the current peer.
     */
    String getCurrentPeer();

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
     * To store some key-value info for a peer.
     * @param id
     * @param info properties of the peer.
     */
    void storeInfoForPeer(String id, Map<String, String> info);

    /**
     * To load info associated to the peer.
     * @param id
     * @return properties of the peer
     */
    Map<String, String> loadInfoForPeer(String id);

    interface EventCallback {

        /**
         * Called whenever an event is fired. This method should not access external resources and it must not be
         * expensive. Inside this method you cannot call other methods of the same {@link GroupMembershipHandler}.
         *
         * @param eventId
         */
        void eventFired(String eventId);

        /**
         * Called whenever ZK connection has been re-established.
         */
        void reconnected();
    }
}
