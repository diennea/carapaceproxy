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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Test for {@link HerdDBConfigurationStore} running in cluster mode, where the replica count of a custom
 * tablespace comes from the configuration instead of being fixed to one.
 * <br>
 * Two nodes are needed: HerdDB writes the tablespace log to a BookKeeper ledger whose ensemble is the
 * expected replica count, so a two-replica tablespace does not boot with a single bookie.
 *
 * @author niccolo.maltoni
 */
public class ClusterConfigurationStoreTest {

    private static final String TABLESPACE_TIMEOUT_PROPERTY = "herd.waitfortablespace.timeout";
    private static final String TABLESPACE = "carapace_cluster_test";
    private static final String ADMIN_USERNAME = "theusername";
    private static final String ADMIN_PASSWORD = "thepassword";
    private static final int REPLICATION_FACTOR = 2;
    private static final int MIN_PORT = 12000;
    private static final int MAX_PORT = 20000;
    private static final int MAX_PORT_ATTEMPTS = 50;

    private static final Set<Integer> HANDED_OUT_PORTS = ConcurrentHashMap.newKeySet();

    private static String previousTableSpaceTimeout;

    /**
     * Shortens the wait of {@link HerdDBConfigurationStore} on the tablespace, so that a broken setup fails the
     * test instead of hanging on the five minutes it waits by default. The store reads the property once, when
     * its class is loaded, so this only takes effect while this class is the first to use the store in the fork,
     * which is what surefire does here by not reusing forks.
     */
    @BeforeClass
    public static void shortenTableSpaceTimeout() {
        previousTableSpaceTimeout = System.setProperty(TABLESPACE_TIMEOUT_PROPERTY, "60000");
    }

    /**
     * Restores the timeout, as the property is process wide and this class shares the JVM with other tests
     * whenever surefire is configured to reuse forks.
     */
    @AfterClass
    public static void restoreTableSpaceTimeout() {
        if (previousTableSpaceTimeout == null) {
            System.clearProperty(TABLESPACE_TIMEOUT_PROPERTY);
        } else {
            System.setProperty(TABLESPACE_TIMEOUT_PROPERTY, previousTableSpaceTimeout);
        }
    }

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private ConfigurationStore peer;
    private ConfigurationStore store;

    @After
    public void after() {
        if (store != null) {
            store.close();
        }
        if (peer != null) {
            peer.close();
        }
    }

    @Test
    public void testCustomTablespaceReplicaCount() throws Exception {
        File zooKeeperDir = tmpDir.newFolder();
        try (TestingServer zooKeeper = new TestingServer(freePort(), zooKeeperDir)) {
            // the peer stays on the default tablespace and provides the second bookie
            File peerDir = tmpDir.newFolder();
            peer = openStore(zooKeeper, new PropertiesConfigurationStore(nodeProperties()), peerDir);

            Properties props = nodeProperties();
            props.setProperty("db.tablespace", TABLESPACE);
            props.setProperty("replication.factor", REPLICATION_FACTOR + "");
            PropertiesConfigurationStore staticConfiguration = new PropertiesConfigurationStore(props);

            File nodeDir = tmpDir.newFolder();
            store = openStore(zooKeeper, staticConfiguration, nodeDir);
            assertEquals(REPLICATION_FACTOR, expectedReplicaCount(zooKeeper, TABLESPACE));
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
        props.setProperty("db.server.port", freePort() + "");
        props.setProperty("db.server.bookkeeper.port", freePort() + "");
        return props;
    }

    /**
     * Picks a free port above the well known ports and below the ephemeral range, where the operating system
     * could otherwise hand the same port to a client socket between this check and the bind. Fixed low ports are
     * no good either: 7000, the HerdDB default, is taken by the AirPlay receiver on macOS. Ports already returned
     * are never returned again, as the probe socket is closed before the caller binds its own.
     *
     * @return a port that was free a moment ago, and that this method never returned before
     * @throws IOException if no free port is found
     */
    private static int freePort() throws IOException {
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(MIN_PORT, MAX_PORT);
            if (!HANDED_OUT_PORTS.add(port)) {
                continue;
            }
            try (ServerSocket socket = new ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (IOException taken) {
                // keep looking
            }
        }
        throw new IOException("no free port available in [" + MIN_PORT + ", " + MAX_PORT + ")");
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
                assertTrue("tablespace " + tableSpace + " not found", rs.next());
                return rs.getInt(1);
            }
        }
    }
}
