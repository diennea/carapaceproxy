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
package org.carapaceproxy;

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChainData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.configstore.StoreNotReadyException;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ListeningChannel;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.config.RestoreInProgressException;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * A restore on a two-node cluster: the other peer reloads the restored state when the restore signal goes away.
 */
public class ClusterRestoreTest {

    private static final String DOMAIN = "app.example.test";

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private TestingServer zooKeeper;
    private HttpProxyServer node1;
    private HttpProxyServer node2;

    @After
    public void stop() throws Exception {
        for (AutoCloseable closeable : new AutoCloseable[]{node2, node1, zooKeeper}) {
            if (closeable != null) {
                closeable.close();
            }
        }
    }

    private HttpProxyServer startNode(String peerId, File basePath, int listenersOffsetPort) throws Exception {
        final Properties boot = new Properties();
        boot.put("listener.offset.port", String.valueOf(listenersOffsetPort));
        boot.put("config.type", "database");
        boot.put("mode", "cluster");
        boot.put("peer.id", peerId);
        boot.put("zkAddress", zooKeeper.getConnectString());
        boot.put("db.bookie.allowLoopback", "true");
        boot.put("db.server.port", String.valueOf(TestUtils.getFreeNonEphemeralPort()));
        boot.put("db.server.bookkeeper.port", String.valueOf(TestUtils.getFreeNonEphemeralPort()));
        final HttpProxyServer node = new HttpProxyServer(StandardEndpointMapper::new, basePath);
        node.configureAtBoot(new PropertiesConfigurationStore(boot));
        node.start();
        return node;
    }

    private static void apply(HttpProxyServer node, Map<String, String> configuration) throws Exception {
        final Properties properties = new Properties();
        properties.putAll(configuration);
        node.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(properties));
    }

    private static StoreSnapshot withConfig(StoreSnapshot snapshot, String key, String value) {
        final Map<String, String> config = new HashMap<>(snapshot.config());
        config.put(key, value);
        return new StoreSnapshot(config, snapshot.keyPairs(), snapshot.acmeAccountKeys(), snapshot.certificates());
    }

    @Test
    public void testPeerReloadsTheRestoredState() throws Exception {
        zooKeeper = new TestingServer(TestUtils.getFreeNonEphemeralPort(), tmpDir.newFolder());
        final int port = TestUtils.getFreeNonEphemeralPort();
        node1 = startNode("node1", tmpDir.newFolder("node1"), 0);
        // both nodes on this host: the peer listens on another port
        node2 = startNode("node2", tmpDir.newFolder("node2"), TestUtils.getFreeNonEphemeralPort() - port);
        // one cluster for both scenarios: the first leaves no file nor retry behind
        writeRefusedWhileThePeerWaitsToReload();
        TestUtils.waitForCondition(() -> !node1.isRestoreInProgress() && !node2.isRestoreInProgress(), 30);
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(zooKeeper.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertNull(client.checkExists().forPath("/proxy/restore-in-progress"));
        }
        peerMissingABackendCaKeepsItsListenersUntilTheFileArrives(port);
    }

    @Test
    public void testRestoreRefusedWhenThePeersCannotBeRead() throws Exception {
        zooKeeper = new TestingServer(TestUtils.getFreeNonEphemeralPort(), tmpDir.newFolder());
        node1 = startNode("node1", tmpDir.newFolder("node1"), 0);
        final StoreSnapshot archive = withConfig(node1.backupState(), "healthmanager.period", "25");
        final int period = node1.getBackendHealthManager().getPeriod();
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(zooKeeper.getConnectString(), new RetryOneTime(100))) {
            client.start();
            // a listed peer whose information cannot be read
            client.create().forPath("/proxy/peers/ghost", "not json".getBytes(StandardCharsets.UTF_8));
            assertThrows(StoreNotReadyException.class, () -> node1.restoreState(archive, null, null));
            client.delete().forPath("/proxy/peers/ghost");
            // a list without this node, as read when ZooKeeper does not answer
            client.delete().forPath("/proxy/peers/node1");
            assertThrows(StoreNotReadyException.class, () -> node1.restoreState(archive, null, null));
        }
        assertFalse(node1.isRestoreInProgress());
        assertEquals(period, node1.getBackendHealthManager().getPeriod());
    }

    private void writeRefusedWhileThePeerWaitsToReload() throws Exception {
        apply(node1, Map.of("healthmanager.period", "20"));
        TestUtils.waitForCondition(() -> node2.getBackendHealthManager().getPeriod() == 20, 30);
        final StoreSnapshot archive = withConfig(node1.backupState(), "healthmanager.period", "25");

        // a write on the peer that holds its configuration lock until released
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);
        final Properties slow = new Properties();
        slow.put("healthmanager.period", "40");
        final CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                node2.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(slow) {
                    @Override
                    public String getProperty(String key, String defaultValue) {
                        if (armed.getAndSet(false)) {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException err) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return super.getProperty(key, defaultValue);
                    }
                });
            } catch (Exception err) {
                throw new RuntimeException(err);
            }
        });
        try {
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            assertTrue(node1.restoreState(archive, null, null).ok());
            // the signal is gone: the reload on the peer waits for the lock held by the write
            TestUtils.waitForCondition(ClusterRestoreTest::reloadWaitsForTheLock, 30);
        } finally {
            release.countDown();
        }
        // the write is refused at its commit, after the reload was asked
        final ExecutionException refused = assertThrows(ExecutionException.class, write::get);
        assertTrue(refused.getCause().getCause() instanceof RestoreInProgressException);
        TestUtils.waitForCondition(() -> !node2.isRestoreInProgress() && node2.getBackendHealthManager().getPeriod() == 25, 30);
    }

    private void peerMissingABackendCaKeepsItsListenersUntilTheFileArrives(int port) throws Exception {
        final File localStore = tmpDir.newFolder("certificates");
        apply(node1, Map.of(
                "healthmanager.period", "21",
                "listener.1.host", "localhost",
                "listener.1.port", String.valueOf(port),
                "certificate.1.hostname", DOMAIN,
                "certificate.1.mode", "acme",
                // only the other peer writes the local certificate files
                "dynamiccertificatesmanager.localcertificates.store.path", localStore.getAbsolutePath(),
                "dynamiccertificatesmanager.localcertificates.peers.ids", "node2"));
        TestUtils.waitForCondition(() -> node2.getBackendHealthManager().getPeriod() == 21, 30);
        node1.updateDynamicCertificateForDomain(new CertificateData(DOMAIN, Base64.getEncoder().encodeToString(generateSampleChainData()),
                DynamicCertificateState.AVAILABLE));
        node1.getDynamicConfigurationStore().saveKeyPairForDomain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), DOMAIN, false);
        // a backend CA present on the node that restores only, relative to the base path of each node
        TestUtils.deployResource("ca.p12", new File(tmpDir.getRoot(), "node1"));
        StoreSnapshot archive = withConfig(node1.backupState(), "healthmanager.period", "26");
        archive = withConfig(archive, "backend.1.id", "secured");
        archive = withConfig(archive, "backend.1.enabled", "true");
        archive = withConfig(archive, "backend.1.ssl", "true");
        archive = withConfig(archive, "backend.1.cacertificate", "ca.p12");
        archive = withConfig(archive, "backend.1.cacertificatepassword", "changeit");
        final String backedUpChain = archive.certificates().get(DOMAIN).chain();

        // change both the configuration and the certificate
        node1.updateDynamicCertificateForDomain(new CertificateData(DOMAIN, Base64.getEncoder().encodeToString(generateSampleChainData()),
                DynamicCertificateState.AVAILABLE));
        final Map<String, String> changed = new HashMap<>(node1.backupState().config());
        changed.put("healthmanager.period", "30");
        apply(node1, changed);
        // the whole apply is done, listeners included, once the configuration is swapped
        TestUtils.waitForCondition(() -> node2.getCurrentConfiguration().getHealthProbePeriod() == 30
                && node2.getListeners().getListeningChannels().size() == 1, 30);
        final ListeningChannel listening = node2.getListeners().getListeningChannels().values().iterator().next();
        final File fullChain = new File(new File(localStore, DOMAIN), "fullchain.pem");
        assertFalse(fullChain.exists());

        assertTrue(node1.restoreState(archive, null, null).ok());
        assertEquals(26, node1.getBackendHealthManager().getPeriod());
        TestUtils.waitForCondition(() -> !node1.isRestoreInProgress(), 30);
        // the apply would load the CA after restarting every listener: the peer checks first and touches nothing
        Thread.sleep(3_000);
        assertTrue(node2.isRestoreInProgress());
        assertEquals(30, node2.getBackendHealthManager().getPeriod());
        assertSame(listening, node2.getListeners().getListeningChannels().values().iterator().next());

        // the file arrives: the next retry, every 30 seconds, applies the restored state
        TestUtils.deployResource("ca.p12", new File(tmpDir.getRoot(), "node2"));
        TestUtils.waitForCondition(() -> !node2.isRestoreInProgress() && node2.getBackendHealthManager().getPeriod() == 26, 90);
        assertEquals(backedUpChain, node2.getDynamicCertificatesManager().getCertificateDataForDomain(DOMAIN).getChain());
        // the local files are rewritten after the reload, with the restored chain
        assertTrue(fullChain.exists());
    }

    private static boolean reloadWaitsForTheLock() {
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey().getName().equals("restore-reload") && e.getKey().getState() == Thread.State.WAITING)
                .anyMatch(e -> Arrays.stream(e.getValue()).anyMatch(f -> f.getMethodName().equals("reloadRestoredState")));
    }
}
