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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.curator.test.TestingServer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author eolivelli
 */
public class ZooKeeperGroupMembershipHandlerTest {

    String peerId1 = "p1";
    String peerId2 = "p2";
    String peerId3 = "p3";

    @Test
    public void testPeerDiscovery() throws Exception {
        try (TestingServer testingServer = new TestingServer(2181);) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, peerId1);
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, peerId2);) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom1);
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom2);

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, peerId3);) {
                    peer3.start();
                    peersFrom1 = peer1.getPeers();
                    peersFrom2 = peer2.getPeers();
                    List<String> peersFrom3 = peer3.getPeers();
                    assertEquals(Arrays.asList(peerId1, peerId2, peerId3), peersFrom1);
                    assertEquals(Arrays.asList(peerId1, peerId2, peerId3), peersFrom2);
                    assertEquals(Arrays.asList(peerId1, peerId2, peerId3), peersFrom3);
                }

                // peer3 exits
                peersFrom1 = peer1.getPeers();
                peersFrom2 = peer2.getPeers();
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom1);
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom2);

            }
        }
    }

    @Test
    public void testWatchEvent() throws Exception {
        try (TestingServer testingServer = new TestingServer(2181);) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, peerId1);
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, peerId2);) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom1);
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom2);

                AtomicInteger eventFired2 = new AtomicInteger();;
                peer2.watchEvent("foo", (eventID) -> {
                    eventFired2.incrementAndGet();
                });

                peer1.fireEvent("foo");

                for (int i = 0; i < 10; i++) {
                    if (eventFired2.get() == 1) {
                        break;
                    }
                    Thread.sleep(100);
                }
                assertEquals(1, eventFired2.get());

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, peerId3);) {
                    peer3.start();

                    AtomicInteger eventFired3 = new AtomicInteger();
                    peer3.watchEvent("foo", (eventID) -> {
                        eventFired3.incrementAndGet();
                    });

                    peer1.fireEvent("foo");

                    for (int i = 0; i < 10; i++) {
                        if (eventFired2.get() == 2
                                && eventFired3.get() == 1) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertEquals(1, eventFired3.get());

                    assertEquals(2, eventFired2.get());

                    peer3.fireEvent("foo");

                    for (int i = 0; i < 10; i++) {
                        if (eventFired2.get() == 3
                                && eventFired3.get() == 2) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertEquals(2, eventFired3.get());
                    assertEquals(3, eventFired2.get());

                }

            }
        }
    }
}
