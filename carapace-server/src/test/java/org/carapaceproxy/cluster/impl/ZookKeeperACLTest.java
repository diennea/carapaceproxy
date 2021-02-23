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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.Configuration;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.utils.TestUtils;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ZookKeeperACLTest {

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();
    String peerId1 = "p1";
    String peerId2 = "p2";
    String peerId3 = "p3";

    /**
     *
     */
    @BeforeClass
    public static void setUpEnvironment() {
        File file = new File("src/test/resources/jaas/test_jaas.conf");
        System.setProperty("java.security.auth.login.config", file.getAbsolutePath());
        assertTrue(file.isFile());
        Configuration.getConfiguration().refresh();
    }

    /**
     *
     * @throws InterruptedException
     * @throws IOException
     */
    @AfterClass
    public static void cleanUpEnvironment() throws InterruptedException, IOException {
        System.clearProperty("java.security.auth.login.config");
        Configuration.getConfiguration().refresh();
    }

    @Test
    public void testUseAcl() throws Exception {
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.put("authProvider.1", "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        InstanceSpec def = InstanceSpec.newInstanceSpec();
        InstanceSpec ss = new InstanceSpec(folder.newFolder(), def.getPort(), def.getElectionPort(),
                def.getQuorumPort(), false /*deleteDataDirectoryOnClose*/, def.getServerId(),
                def.getTickTime(), def.getMaxClientCnxns(), customProperties, def.getHostname());

        try (TestingServer testingServer = new TestingServer(ss,
                false);) {
            testingServer.start();
            try (ZooKeeperGroupMembershipHandler peer1 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                    6000, true /*acl */, peerId1, Collections.EMPTY_MAP, new Properties());
                    ZooKeeperGroupMembershipHandler peer2 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                            6000, true /*acl */, peerId2, Collections.EMPTY_MAP, new Properties())) {
                peer1.start();
                peer2.start();
                Map<String, Boolean> peersFrom1 = peer1.getPeers();
                Map<String, Boolean> peersFrom2 = peer2.getPeers();
                assertEquals(Map.of(peerId1, true, peerId2, true), peersFrom1);
                assertEquals(Map.of(peerId1, true, peerId2, true), peersFrom2);

                AtomicInteger eventFired2 = new AtomicInteger();
                peer2.watchEvent("foo", new GroupMembershipHandler.EventCallback() {
                    @Override
                    public void eventFired(String eventId) {
                        eventFired2.incrementAndGet();
                    }

                    @Override
                    public void reconnected() {

                    }
                });

                peer1.fireEvent("foo");

                TestUtils.waitForCondition(() -> {
                    return eventFired2.get() >= 1;
                }, 100);

                assertTrue(eventFired2.get() >= 1);
                eventFired2.set(0);

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, false /*acl */, peerId3, Collections.EMPTY_MAP, new Properties())) {
                    peer3.start();

                    AtomicInteger eventFired3 = new AtomicInteger();
                    peer3.watchEvent("foo", new GroupMembershipHandler.EventCallback() {
                        @Override
                        public void eventFired(String eventId) {
                            eventFired3.incrementAndGet();
                        }

                        @Override
                        public void reconnected() {

                        }
                    });

                    peer1.fireEvent("foo");

                    TestUtils.waitForCondition(() -> {
                        return (eventFired2.get() >= 1
                                && eventFired3.get() >= 1);
                    }, 100);

                    assertTrue(eventFired3.get() >= 1);
                    assertTrue(eventFired2.get() >= 1);

                    eventFired3.set(0);
                    eventFired2.set(0);

                    peer3.fireEvent("foo");

                    for (int i = 0; i < 10; i++) {
                        if (eventFired2.get() >= 1
                                && eventFired3.get() >= 1) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertTrue(eventFired3.get() == 0); // self events are not fired
                    assertTrue(eventFired2.get() > 0);
                }

            }
        }
    }
}
