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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.ZooKeeper;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.utils.TestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ZooKeeperGroupMembershipHandlerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();
    String peerId1 = "p1";
    String peerId2 = "p2";
    String peerId3 = "p3";

    @Test
    public void testPeerDiscovery() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir.newFolder())) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom1);
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom2);

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, false /*acl */, peerId3, Collections.EMPTY_MAP, new Properties())) {
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DummyObject {

        private int number;
        private String string;

    }

    @Test
    public void testWatchEvent() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir.newFolder());) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                List<String> peersFrom1 = peer1.getPeers();
                List<String> peersFrom2 = peer2.getPeers();
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom1);
                assertEquals(Arrays.asList(peerId1, peerId2), peersFrom2);

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
                TestUtils.waitForCondition(() -> {
                    return eventFired2.get() >= 1;
                }, 100);
                assertTrue(eventFired2.get() >= 1);
                assertTrue(dataRes2.isEmpty());
                eventFired2.set(0);

                peer1.fireEvent("foo", Map.of(
                        "number", 1,
                        "string", "mystring",
                        "list", List.of(1, 2),
                        "obj", new DummyObject(1, "s")
                ));
                TestUtils.waitForCondition(() -> {
                    return eventFired2.get() >= 1;
                }, 100);
                assertTrue(eventFired2.get() >= 1);
                assertTrue(((int) dataRes2.get("number")) == 1);
                assertTrue(dataRes2.get("string").equals("mystring"));
                assertTrue(((List<Integer>) dataRes2.get("list")).containsAll(List.of(1, 2)));
                assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("number").equals(1));
                assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("string").equals("s"));
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
                    TestUtils.waitForCondition(() -> {
                        return (eventFired2.get() >= 1
                                && eventFired3.get() >= 1);
                    }, 100);
                    assertTrue(eventFired2.get() >= 1);
                    assertTrue(dataRes2.isEmpty());
                    eventFired2.set(0);
                    assertTrue(eventFired3.get() >= 1);
                    assertTrue(dataRes3.isEmpty());
                    eventFired3.set(0);

                    peer1.fireEvent("foo", Map.of(
                            "number", 1,
                            "string", "mystring",
                            "list", List.of("1", "2"),
                            "obj", new DummyObject(1, "s")
                    ));
                    TestUtils.waitForCondition(() -> {
                        return (eventFired2.get() >= 1
                                && eventFired3.get() >= 1);
                    }, 100);
                    assertTrue(eventFired2.get() >= 1);
                    assertTrue(((int) dataRes2.get("number")) == 1);
                    assertTrue(dataRes2.get("string").equals("mystring"));
                    assertTrue(((List<String>) dataRes2.get("list")).containsAll(List.of("1", "2")));
                    assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("number").equals(1));
                    assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("string").equals("s"));
                    eventFired2.set(0);
                    dataRes2.clear();
                    assertTrue(eventFired3.get() >= 1);
                    assertTrue(((int) dataRes3.get("number")) == 1);
                    assertTrue(dataRes3.get("string").equals("mystring"));
                    assertTrue(((List<Integer>) dataRes3.get("list")).containsAll(List.of("1", "2")));
                    assertTrue(((Map<String, Object>) dataRes3.get("obj")).get("number").equals(1));
                    assertTrue(((Map<String, Object>) dataRes3.get("obj")).get("string").equals("s"));
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
                    assertTrue(eventFired2.get() > 0);
                    assertTrue(((int) dataRes2.get("number")) == 1);
                    assertTrue(dataRes2.get("string").equals("mystring"));
                    assertTrue(((List<Integer>) dataRes2.get("list")).containsAll(List.of(1, 2)));
                    assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("number").equals(1));
                    assertTrue(((Map<String, Object>) dataRes2.get("obj")).get("string").equals("s"));
                    // self events are not fired
                    assertTrue(eventFired3.get() == 0);
                    assertTrue(dataRes3.isEmpty());
                }

            }
        }
    }

    @Test
    public void testPeerInfo() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir.newFolder());) {
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
                    assertEquals("peer1", info.get("name"));
                    info = peer1.loadInfoForPeer(peerId2);
                    assertEquals("peer2", info.get("name"));

                    peer1.storeLocalPeerInfo(Map.of("name", "newpeer1", "address", "localhost"));
                    info = peer1.loadInfoForPeer(peerId1);
                    assertEquals("newpeer1", info.get("name"));
                    assertEquals("localhost", info.get("address"));

                    peer1.storeLocalPeerInfo(Collections.EMPTY_MAP);
                    info = peer1.loadInfoForPeer(peerId1);
                    assertTrue(info.isEmpty());
                    peer1.storeLocalPeerInfo(Map.of("port", "8080"));
                    info = peer1.loadInfoForPeer(peerId1);
                    assertNull(info.get("name"));
                    peer1.storeLocalPeerInfo(null); // discarded
                    info = peer1.loadInfoForPeer(peerId1);
                    assertNull(info);
                }

                // OP on peer2
                {
                    peer2.storeLocalPeerInfo(Map.of("name", "peer2", "address", "localhost:8080"));
                    Map<String, String> info = peer2.loadInfoForPeer(peerId2);
                    assertEquals("peer2", info.get("name"));
                    assertEquals("localhost:8080", info.get("address"));
                    info = peer2.loadInfoForPeer(peerId1);
                    assertNull(info);
                }
            }
        }
    }

    @Test
    public void testRestoreSignal() throws Exception {
        try (TestingServer testingServer = new TestingServer(TestUtils.getFreeNonEphemeralPort(), tmpDir.newFolder())) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                AtomicInteger reloads1 = new AtomicInteger();
                AtomicInteger reloads2 = new AtomicInteger();
                AtomicBoolean flagOnAtCallback = new AtomicBoolean();
                peer1.watchRestoreSignal(reloads1::incrementAndGet);
                peer2.watchRestoreSignal(() -> {
                    flagOnAtCallback.set(peer2.isRestoreInProgress());
                    reloads2.incrementAndGet();
                });
                assertFalse(peer1.isRestoreInProgress());
                assertFalse(peer2.isRestoreInProgress());

                GroupMembershipHandler.RestoreSignal signal = peer1.raiseRestoreSignal();
                signal.ensureHeld();
                TestUtils.waitForCondition(() -> peer1.isRestoreInProgress() && peer2.isRestoreInProgress(), 10);
                assertThrows(ConfigurationChangeInProgressException.class, peer2::raiseRestoreSignal);
                assertThrows(ConfigurationChangeInProgressException.class, peer1::raiseRestoreSignal);

                signal.close();
                assertThrows(IllegalStateException.class, signal::ensureHeld);
                // the creator applied the restore itself: only the other peer reloads, before its flag drops
                TestUtils.waitForCondition(() -> !peer1.isRestoreInProgress() && !peer2.isRestoreInProgress(), 10);
                assertEquals(0, reloads1.get());
                assertEquals(1, reloads2.get());
                assertTrue(flagOnAtCallback.get());

                // two restores in a row on the same node are not confused
                final int reloadsBefore = reloads2.get();
                peer1.raiseRestoreSignal().close();
                try (GroupMembershipHandler.RestoreSignal second = peer1.raiseRestoreSignal()) {
                    second.ensureHeld();
                    // back to back, the other peer may never see the first signal: only the last one must reach it
                    TestUtils.waitForCondition(peer2::isRestoreInProgress, 10);
                }
                TestUtils.waitForCondition(() -> reloads2.get() > reloadsBefore && !peer2.isRestoreInProgress(), 10);
                assertEquals(0, reloads1.get());

                // the signal goes with the session of the node that raised it
                final int reloadsBeforeExpiry = reloads2.get();
                final GroupMembershipHandler.RestoreSignal lost = peer1.raiseRestoreSignal();
                TestUtils.waitForCondition(peer2::isRestoreInProgress, 10);
                KillSession.kill(zooKeeperOf(peer1));
                TestUtils.waitForCondition(() -> reloads2.get() > reloadsBeforeExpiry && !peer2.isRestoreInProgress(), 30);
                // the client learns about the expiry asynchronously
                TestUtils.waitForCondition(() -> {
                    try {
                        lost.ensureHeld();
                        return false;
                    } catch (IllegalStateException expected) {
                        return true;
                    }
                }, 30);
                assertEquals(0, reloads1.get());

                // the signal is free again; the stale handle of the first node never clears it
                try (GroupMembershipHandler.RestoreSignal other = peer2.raiseRestoreSignal()) {
                    TestUtils.waitForCondition(peer1::isRestoreInProgress, 10);
                    lost.close();
                    other.ensureHeld();
                }
                TestUtils.waitForCondition(() -> reloads1.get() == 1 && !peer1.isRestoreInProgress(), 10);
                // and its node can restore again
                peer1.raiseRestoreSignal().close();
            }
        }
    }

    @Test
    public void testExecuteInMutexKeepsTheInterrupt() throws Exception {
        try (TestingServer testingServer = new TestingServer(TestUtils.getFreeNonEphemeralPort(), tmpDir.newFolder())) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, false /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, false /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                final CountDownLatch held = new CountDownLatch(1);
                final CountDownLatch release = new CountDownLatch(1);
                final Thread holder = new Thread(() -> peer1.executeInMutex("m", 10, () -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                holder.start();
                assertTrue(held.await(10, TimeUnit.SECONDS));

                final AtomicBoolean ran = new AtomicBoolean();
                final AtomicBoolean interrupted = new AtomicBoolean();
                final Thread waiter = new Thread(() -> {
                    peer2.executeInMutex("m", 30, () -> ran.set(true));
                    interrupted.set(Thread.currentThread().isInterrupted());
                });
                waiter.start();
                Thread.sleep(500); // let it block on the mutex
                waiter.interrupt();
                waiter.join(10_000);
                release.countDown();
                holder.join(10_000);

                assertFalse(waiter.isAlive());
                assertTrue(interrupted.get());
                assertFalse(ran.get());
            }
        }
    }

    private static ZooKeeper zooKeeperOf(ZooKeeperGroupMembershipHandler handler) throws Exception {
        Field client = ZooKeeperGroupMembershipHandler.class.getDeclaredField("client");
        client.setAccessible(true);
        return ((CuratorFramework) client.get(handler)).getZookeeperClient().getZooKeeper();
    }
}
