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
import org.carapaceproxy.cluster.GroupMembershipHandler;

/**
 * Noop implementation
 *
 * @author eolivelli
 */
public class NullGroupMembershipHandler implements GroupMembershipHandler {

    private Map<String, String> peerInfo = Map.of("address", "localhost");
    private final String peerId = "local";

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
        runnable.run();
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
