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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.Configuration;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.utils.TestUtils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 * @author enrico.olivelli
 */
public class ZookKeeperACLIT {

    @TempDir
    public static File folder;

    @TempDir
    public File tmpDir;
    String peerId1 = "p1";
    String peerId2 = "p2";
    String peerId3 = "p3";

    /**
     *
     */
    @BeforeAll
    static void setUpEnvironment() {
        File file = new File("src/test/resources/jaas/test_jaas.conf");
        System.setProperty("java.security.auth.login.config", file.getAbsolutePath());
        assertThat(file).isFile();
        Configuration.getConfiguration().refresh();
    }

    /**
     *
     * @throws InterruptedException
     * @throws IOException
     */
    @AfterAll
    static void cleanUpEnvironment() throws InterruptedException, IOException {
        System.clearProperty("java.security.auth.login.config");
        Configuration.getConfiguration().refresh();
    }

    @Test
    void useAcl() throws Exception {
        Map<String, Object> customProperties = new HashMap<>();
        customProperties.put("authProvider.1", "org.apache.zookeeper.server.auth.SASLAuthenticationProvider");
        InstanceSpec def = InstanceSpec.newInstanceSpec();
        InstanceSpec ss = new InstanceSpec(newFolder(folder, "junit"), def.getPort(), def.getElectionPort(),
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

                peer1.fireEvent("foo", Map.of("data", "mydata"));
                TestUtils.waitForCondition(() -> eventFired2.get() >= 1, 100);
                assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                assertThat(dataRes2).containsEntry("data", "mydata");
                eventFired2.set(0);
                dataRes2.clear();

                try (ZooKeeperGroupMembershipHandler peer3 = new ZooKeeperGroupMembershipHandler(testingServer.getConnectString(),
                        6000, false /*acl */, peerId3, Collections.EMPTY_MAP, new Properties())) {
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

                    peer1.fireEvent("foo", Map.of("data", "mydata"));
                    TestUtils.waitForCondition(() -> (eventFired2.get() >= 1
                                && eventFired3.get() >= 1), 100);
                    assertThat(eventFired2.get()).isGreaterThanOrEqualTo(1);
                    assertThat(dataRes2).containsEntry("data", "mydata");
                    eventFired2.set(0);
                    dataRes2.clear();
                    assertThat(eventFired3.get()).isGreaterThanOrEqualTo(1);
                    assertThat(dataRes3).containsEntry("data", "mydata");
                    eventFired3.set(0);
                    dataRes3.clear();

                    peer3.fireEvent("foo", Map.of("data", "mydata"));
                    for (int i = 0; i < 10; i++) {
                        if (eventFired2.get() >= 1
                                && eventFired3.get() >= 1) {
                            break;
                        }
                        Thread.sleep(100);
                    }
                    assertThat(eventFired2.get()).isGreaterThan(0);
                    assertThat(dataRes2).containsEntry("data", "mydata");
                    // self events are not fired
                    assertThat(eventFired3.get()).isZero();
                    assertThat(dataRes3).isEmpty();
                }

            }
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs) + "-" + System.nanoTime();
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
