/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.configstore;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link HerdDBConfigurationStore} running in cluster mode, where the replica count of a custom
 * tablespace comes from the configuration instead of being fixed to one.
 * <br>
 * Two nodes are needed: HerdDB writes the tablespace log to a BookKeeper ledger whose ensemble is the
 * expected replica count, so a two-replica tablespace does not boot with a single bookie.
 *
 * @author niccolo.maltoni
 */
public class ClusterConfigurationStoreIT {

    private static final String TABLESPACE_TIMEOUT_PROPERTY = "herd.waitfortablespace.timeout";
    private static final String TABLESPACE = "carapace_cluster_test";
    private static final String ADMIN_USERNAME = "theusername";
    private static final String ADMIN_PASSWORD = "thepassword";
    private static final int REPLICATION_FACTOR = 2;

    private static String previousTableSpaceTimeout;

    /**
     * Shortens the wait of {@link HerdDBConfigurationStore} on the tablespace, so that a broken setup fails the
     * test instead of hanging on the five minutes it waits by default. The store reads the property once, when
     * its class is loaded, so this only takes effect while this class is the first to use the store in the fork,
     * which is what failsafe does here by not reusing forks.
     */
    @BeforeAll
    static void shortenTableSpaceTimeout() {
        previousTableSpaceTimeout = System.setProperty(TABLESPACE_TIMEOUT_PROPERTY, "60000");
    }

    /**
     * Restores the timeout, as the property is process wide and this class shares the JVM with other tests
     * whenever failsafe is configured to reuse forks.
     */
    @AfterAll
    static void restoreTableSpaceTimeout() {
        if (previousTableSpaceTimeout == null) {
            System.clearProperty(TABLESPACE_TIMEOUT_PROPERTY);
        } else {
            System.setProperty(TABLESPACE_TIMEOUT_PROPERTY, previousTableSpaceTimeout);
        }
    }

    @TempDir
    public File tmpDir;

    private ConfigurationStore peer;
    private ConfigurationStore store;

    @AfterEach
    void after() {
        if (store != null) {
            store.close();
        }
        if (peer != null) {
            peer.close();
        }
    }

    @Test
    void customTablespaceReplicaCount() throws Exception {
        File zooKeeperDir = newFolder(tmpDir, "junit");
        try (TestingServer zooKeeper = new TestingServer(TestUtils.getFreeNonEphemeralPort(), zooKeeperDir)) {
            // the peer stays on the default tablespace and provides the second bookie
            File peerDir = newFolder(tmpDir, "junit");
            peer = openStore(zooKeeper, new PropertiesConfigurationStore(nodeProperties()), peerDir);

            Properties props = nodeProperties();
            props.setProperty("db.tablespace", TABLESPACE);
            props.setProperty("replication.factor", REPLICATION_FACTOR + "");
            PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

            File nodeDir = newFolder(tmpDir, "junit");
            store = openStore(zooKeeper, staticConfiguration, nodeDir);
            assertThat(expectedReplicaCount(zooKeeper, TABLESPACE)).isEqualTo(REPLICATION_FACTOR);

            // both replicas up: writable, once the follower has reported its state
            await().atMost(30, SECONDS).ignoreException(StoreNotReadyException.class).untilAsserted(store::checkWritable);
            store.checkAnswering();
        }
    }

    /**
     * Builds the configuration of one node, with its own ports so that several of them run in the same JVM.
     *
     * @return the static configuration of a cluster node
     * @throws IOException if no free port can be found
     */
    private static Properties nodeProperties() throws IOException {
        Properties props = new Properties();
        props.setProperty("db.bookie.allowLoopback", "true");
        props.setProperty("db.admin.username", ADMIN_USERNAME);
        props.setProperty("db.admin.password", ADMIN_PASSWORD);
        props.setProperty("db.server.port", TestUtils.getFreeNonEphemeralPort() + "");
        props.setProperty("db.server.bookkeeper.port", TestUtils.getFreeNonEphemeralPort() + "");
        return props;
    }

    /**
     * Opens a configuration store against the cluster.
     *
     * @param zooKeeper           the ZooKeeper the cluster is registered on
     * @param staticConfiguration the static configuration of the node
     * @param baseDir             the data directory of the node
     * @return the store
     */
    private static HerdDBConfigurationStore openStore(
            TestingServer zooKeeper, ConfigurationStore staticConfiguration, File baseDir) {
        return new HerdDBConfigurationStore(
                staticConfiguration, true, zooKeeper.getConnectString(), baseDir, NullStatsLogger.INSTANCE
        );
    }

    /**
     * Reads the replica count HerdDB recorded for the given tablespace.
     *
     * @param zooKeeper  the ZooKeeper the cluster is registered on
     * @param tableSpace the tablespace name
     * @return the value of the {@code expectedreplicacount} column
     * @throws Exception if the tablespace is unknown or the query fails
     */
    private static int expectedReplicaCount(TestingServer zooKeeper, String tableSpace) throws Exception {
        String url = "jdbc:herddb:zookeeper:" + zooKeeper.getConnectString();
        try (Connection con = DriverManager.getConnection(url, ADMIN_USERNAME, ADMIN_PASSWORD);
                PreparedStatement ps = con.prepareStatement(
                        "SELECT expectedreplicacount FROM systablespaces WHERE tablespace_name=?"
                )) {
            ps.setString(1, tableSpace);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("tablespace " + tableSpace + " not found").isTrue();
                return rs.getInt(1);
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
