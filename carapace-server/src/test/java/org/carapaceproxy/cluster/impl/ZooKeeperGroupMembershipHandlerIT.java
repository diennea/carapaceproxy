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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.io.TempDir;

public class ZooKeeperGroupMembershipHandlerIT {

    @TempDir
    public File tmpDir;
    String peerId1 = "p1";
    String peerId2 = "p2";
    String peerId3 = "p3";

    @Test
    void peerDiscovery() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir)) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertThat(peersFrom1).containsExactly(peerId1, peerId2);
                assertThat(peersFrom2).containsExactly(peerId1, peerId2);

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, false /*acl */, peerId3, Collections.EMPTY_MAP, new Properties())) {
                    peer3.start();
                    peersFrom1 = peer1.getPeers();
                    peersFrom2 = peer2.getPeers();
                    List<String> peersFrom3 = peer3.getPeers();
                    assertThat(peersFrom1).containsExactly(peerId1, peerId2, peerId3);
                    assertThat(peersFrom2).containsExactly(peerId1, peerId2, peerId3);
                    assertThat(peersFrom3).containsExactly(peerId1, peerId2, peerId3);
                }

                // peer3 exits
                peersFrom1 = peer1.getPeers();
                peersFrom2 = peer2.getPeers();
                assertThat(peersFrom1).containsExactly(peerId1, peerId2);
                assertThat(peersFrom2).containsExactly(peerId1, peerId2);

            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DummyObject {

        private int number;
        private String string;

    }

    @Test
    void watchEvent() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir);) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertThat(peersFrom1).containsExactly(peerId1, peerId2);
                assertThat(peersFrom2).containsExactly(peerId1, peerId2);

                AtomicInteger eventFired2 = new AtomicInteger();
                Map<String, Object> dataRes2 = new HashMap<>();
                peer2.watchEvent("foo", new GroupMembershipHandler.EventCallback() {
                    @Override
                    public void eventFired(String eventId, Map<String, Object> data) {
                        eventFired2.incrementAndGet();
                        dataRes2.putAll(data);
                    }

                    @Override
                    public void reconnected() {

                    }
                });

                peer1.fireEvent("foo", null);
                TestUtils.waitForCondition(() -> eventFired2.get() >= 1, 100);
                assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                assertThat(dataRes2).isEmpty();
                eventFired2.set(0);

                peer1.fireEvent("foo", Map.of(
                        "number", 1,
                        "string", "mystring",
                        "list", List.of(1, 2),
                        "obj", new DummyObject(1, "s")
                ));
                TestUtils.waitForCondition(() -> eventFired2.get() >= 1, 100);
                assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                assertThat(((int) dataRes2.get("number"))).isOne();
                assertThat(dataRes2).containsEntry("string", "mystring");
                assertThat(((List<Integer>) dataRes2.get("list"))).containsExactly(1, 2);
                assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("number", 1);
                assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("string", "s");
                eventFired2.set(0);
                dataRes2.clear();

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, false /*
                         * acl
                         */, peerId3, Collections.EMPTY_MAP, new Properties())) {
                    peer3.start();

                    AtomicInteger eventFired3 = new AtomicInteger();
                    Map<String, Object> dataRes3 = new HashMap<>();
                    peer3.watchEvent("foo", new GroupMembershipHandler.EventCallback() {
                        @Override
                        public void eventFired(String eventId, Map<String, Object> data) {
                            eventFired3.incrementAndGet();
                            dataRes3.putAll(data);
                        }

                        @Override
                        public void reconnected() {

                        }
                    });

                    peer1.fireEvent("foo", null);
                    TestUtils.waitForCondition(() -> (eventFired2.get() >= 1
                                && eventFired3.get() >= 1), 100);
                    assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                    assertThat(dataRes2).isEmpty();
                    eventFired2.set(0);
                    assertThat(eventFired3.get()).isGreaterThanOrEqualTo(1);
                    assertThat(dataRes3).isEmpty();
                    eventFired3.set(0);

                    peer1.fireEvent("foo", Map.of(
                            "number", 1,
                            "string", "mystring",
                            "list", List.of("1", "2"),
                            "obj", new DummyObject(1, "s")
                    ));
                    TestUtils.waitForCondition(() -> (eventFired2.get() >= 1
                                && eventFired3.get() >= 1), 100);
                    assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                    assertThat(((int) dataRes2.get("number"))).isOne();
                    assertThat(dataRes2).containsEntry("string", "mystring");
                    assertThat(((List<String>) dataRes2.get("list"))).containsExactly("1", "2");
                    assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("number", 1);
                    assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("string", "s");
                    eventFired2.set(0);
                    dataRes2.clear();
                    assertThat(eventFired3.get()).isGreaterThanOrEqualTo(1);
                    assertThat(((int) dataRes3.get("number"))).isOne();
                    assertThat(dataRes3).containsEntry("string", "mystring");
                    assertThat((List<?>) dataRes3.get("list")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("1", "2");
                    assertThat(((Map<String, Object>) dataRes3.get("obj"))).containsEntry("number", 1);
                    assertThat(((Map<String, Object>) dataRes3.get("obj"))).containsEntry("string", "s");
                    eventFired3.set(0);
                    dataRes3.clear();

                    peer3.fireEvent("foo", Map.of(
                            "number", 1,
                            "string", "mystring",
                            "list", List.of(1, 2),
                            "obj", new DummyObject(1, "s")
                    ));
                    for (int i = 0; i < 10; i++) {
                        if (eventFired2.get() >= 1
                                && eventFired3.get() >= 1) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertThat(eventFired2.get()).isGreaterThan(0);
                    assertThat(((int) dataRes2.get("number"))).isOne();
                    assertThat(dataRes2).containsEntry("string", "mystring");
                    assertThat(((List<Integer>) dataRes2.get("list"))).containsExactly(1, 2);
                    assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("number", 1);
                    assertThat(((Map<String, Object>) dataRes2.get("obj"))).containsEntry("string", "s");
                    // self events are not fired
                    assertThat(eventFired3.get()).isZero();
                    assertThat(dataRes3).isEmpty();
                }

            }
        }
    }

    @Test
    void peerInfo() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir);) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Map.of("name", "peer1"), new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Map.of("name", "peer2"), new Properties())) {
                peer1.start();
                peer2.start();

                // OP on peer1
                {
                    Map<String, String> info = peer1.loadInfoForPeer(peerId1);
                    assertThat(info).containsEntry("name", "peer1");
                    info = peer1.loadInfoForPeer(peerId2);
                    assertThat(info).containsEntry("name", "peer2");

                    peer1.storeLocalPeerInfo(Map.of("name", "newpeer1", "address", "localhost"));
                    info = peer1.loadInfoForPeer(peerId1);
                    assertThat(info)
                            .containsEntry("name", "newpeer1")
                            .containsEntry("address", "localhost");

                    peer1.storeLocalPeerInfo(Collections.EMPTY_MAP);
                    info = peer1.loadInfoForPeer(peerId1);
                    assertThat(info).isEmpty();
                    peer1.storeLocalPeerInfo(Map.of("port", "8080"));
                    info = peer1.loadInfoForPeer(peerId1);
                    assertThat(info.get("name")).isNull();
                    peer1.storeLocalPeerInfo(null); // discarded
                    info = peer1.loadInfoForPeer(peerId1);
                    assertThat(info).isNull();
                }

                // OP on peer2
                {
                    peer2.storeLocalPeerInfo(Map.of("name", "peer2", "address", "localhost:8080"));
                    Map<String, String> info = peer2.loadInfoForPeer(peerId2);
                    assertThat(info)
                            .containsEntry("name", "peer2")
                            .containsEntry("address", "localhost:8080");
                    info = peer2.loadInfoForPeer(peerId1);
                    assertThat(info).isNull();
                }
            }
        }
    }

}
