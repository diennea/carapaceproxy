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
import java.util.List;
import org.carapaceproxy.cluster.GroupMembershipHandler;

/**
 * Noop implementation
 *
 * @author eolivelli
 */
public class NullGroupMembershipHandler implements GroupMembershipHandler {

    @Override
    public void start(String peerID) {
    }

    @Override
    public void watchEvent(String eventId, EventCallback callback) {
    }

    @Override
    public void fireEvent(String eventId) {
    }

    @Override
    public List<String> getPeers() {
        return Collections.emptyList();
    }

    @Override
    public String describePeer(String peerId) {
        return "";
    }

    @Override
    public void stop() {
    }

}
