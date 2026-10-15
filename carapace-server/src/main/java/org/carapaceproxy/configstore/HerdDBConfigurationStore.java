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

import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePrivateKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePublicKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.toCertificateData;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.toCertificateRow;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import herddb.client.ClientConfiguration;
import herddb.client.ClientSideMetadataProviderException;
import herddb.client.HDBConnection;
import herddb.client.HDBException;
import herddb.jdbc.BasicHerdDBDataSource;
import herddb.jdbc.HerdDBConnection;
import herddb.jdbc.HerdDBEmbeddedDataSource;
import herddb.model.TableSpace;
import herddb.security.SimpleSingleUserManager;
import herddb.server.ServerConfiguration;
import java.io.File;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import org.apache.bookkeeper.stats.StatsLogger;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration storage implementation tha reads the configuration from a JDBC database,
 * i.e., and {@link BasicHerdDBDataSource HerdDB instance}.
 * <br>
 * This configuration store is able to commit edits to the database and track versions of configuration properties.
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "https://github.com/spotbugs/spotbugs/issues/432")
public class HerdDBConfigurationStore implements ConfigurationStore {

    private static final int TABLESPACE_TIMEOUT = Integer.getInteger("herd.waitfortablespace.timeout", 1000 * 60 * 5);

    public static final String ACME_USER_KEY = "_acmeuserkey";

    private static final String CONFIG_TABLE_NAME = "proxy_config";
    private static final String CREATE_CONFIG_TABLE =
            "CREATE TABLE " + CONFIG_TABLE_NAME + "(pname string primary key, pvalue string)";
    private static final String SELECT_ALL_FROM_CONFIG_TABLE =
            "SELECT pname, pvalue FROM " + CONFIG_TABLE_NAME;
    private static final String UPDATE_CONFIG_TABLE =
            "UPDATE " + CONFIG_TABLE_NAME + " SET pvalue=? WHERE pname=?";
    private static final String DELETE_FROM_CONFIG_TABLE =
            "DELETE FROM " + CONFIG_TABLE_NAME + " WHERE pname=?";
    private static final String INSERT_INTO_CONFIG_TABLE =
            "INSERT INTO " + CONFIG_TABLE_NAME + "(pname, pvalue) VALUES (?, ?)";

    private static final String KEYPAIR_TABLE_NAME = "keypairs";
    private static final String CREATE_KEYPAIR_TABLE =
            "CREATE TABLE " + KEYPAIR_TABLE_NAME + "(domain string primary key, privateKey string, publicKey string)";
    private static final String SELECT_FROM_KEYPAIR_TABLE =
            "SELECT privateKey, publicKey FROM " + KEYPAIR_TABLE_NAME + " WHERE domain=?";
    private static final String SELECT_ALL_FROM_KEYPAIR_TABLE =
            "SELECT domain, privateKey, publicKey FROM " + KEYPAIR_TABLE_NAME;
    private static final String UPDATE_KEYPAIR_TABLE =
            "UPDATE " + KEYPAIR_TABLE_NAME + " SET privateKey=?, publicKey=? WHERE domain=?";
    private static final String INSERT_INTO_KEYPAIR_TABLE =
            "INSERT INTO " + KEYPAIR_TABLE_NAME + "(domain, privateKey, publicKey) VALUES (?, ?, ?)";

    private static final String DIGITAL_CERTIFICATES_TABLE_NAME = "digital_certificates";
    private static final String CREATE_DIGITAL_CERTIFICATES_TABLE =
            "CREATE TABLE " + DIGITAL_CERTIFICATES_TABLE_NAME + "("
            + "domain string primary key, "
            + "subjectAltNames string, "
            + "chain string, "
            + "state string, "
            + "pendingOrder string, "
            + "pendingChallenges string, "
            + "attemptCount int, "
            + "message string)";
    private static final String SELECT_ALL_FROM_DIGITAL_CERTIFICATES_TABLE =
            "SELECT domain, subjectAltNames, chain, state, pendingOrder, pendingChallenges, attemptCount, message"
            + " FROM " + DIGITAL_CERTIFICATES_TABLE_NAME;
    private static final String SELECT_FROM_DIGITAL_CERTIFICATES_TABLE = SELECT_ALL_FROM_DIGITAL_CERTIFICATES_TABLE + " WHERE domain=?";
    private static final String UPDATE_DIGITAL_CERTIFICATES_TABLE =
            "UPDATE " + DIGITAL_CERTIFICATES_TABLE_NAME
            + " SET subjectAltNames=?, chain=?, state=?, pendingOrder=?, pendingChallenges=?, attemptCount=?, message=?"
            + " WHERE domain=?";
    private static final String INSERT_INTO_DIGITAL_CERTIFICATES_TABLE =
            "INSERT INTO " + DIGITAL_CERTIFICATES_TABLE_NAME
            + "(subjectAltNames, chain, state, pendingOrder, pendingChallenges, attemptCount, message, domain)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String REMOVE_DIGITAL_CERTIFICATES_TABLE =
            "DELETE FROM " + DIGITAL_CERTIFICATES_TABLE_NAME + " WHERE domain=?";

    private static final String ACME_CHALLENGE_TOKENS_TABLE_NAME = "acme_challenge_tokens";
    private static final String CREATE_ACME_CHALLENGE_TOKENS_TABLE =
            "CREATE TABLE " + ACME_CHALLENGE_TOKENS_TABLE_NAME + "(id string primary key, data string)";
    private static final String SELECT_FROM_ACME_CHALLENGE_TOKENS_TABLE =
            "SELECT data FROM " + ACME_CHALLENGE_TOKENS_TABLE_NAME + " WHERE id=?";
    private static final String INSERT_INTO_ACME_CHALLENGE_TOKENS_TABLE =
            "INSERT INTO " + ACME_CHALLENGE_TOKENS_TABLE_NAME + "(id, data) VALUES (?, ?)";
    private static final String DELETE_FROM_ACME_CHALLENGE_TOKENS_TABLE =
            "DELETE FROM " + ACME_CHALLENGE_TOKENS_TABLE_NAME + " WHERE id=?";

    // one row, written only by replaceState: tells a peer that the state changed under it
    private static final String STATE_GENERATION_TABLE_NAME = "state_generation";
    private static final String STATE_GENERATION_ID = "state";
    private static final String CREATE_STATE_GENERATION_TABLE =
            "CREATE TABLE " + STATE_GENERATION_TABLE_NAME + "(id string primary key, generation string)";
    private static final String SELECT_FROM_STATE_GENERATION_TABLE =
            "SELECT generation FROM " + STATE_GENERATION_TABLE_NAME + " WHERE id=?";
    private static final String INSERT_INTO_STATE_GENERATION_TABLE =
            "INSERT INTO " + STATE_GENERATION_TABLE_NAME + "(generation, id) VALUES (?, ?)";

    private static final String TABLESPACE_PROPERTY = "db.tablespace";
    private static final Pattern VALID_TABLESPACE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    // CREATE TABLESPACE takes no bind parameter, hence the interpolation of the already validated name
    private static final String CREATE_TABLESPACE = "CREATE TABLESPACE '%s','expectedreplicacount:%d'";
    private static final String SELECT_FROM_TABLESPACES_TABLE =
            "SELECT tablespace_name FROM systablespaces WHERE tablespace_name=?";
    // system tables of HerdDB 0.26.1 (SystablespacesTableManager, SystablespacereplicastateTableManager)
    private static final String SELECT_ALL_FROM_TABLESPACES_TABLE =
            "SELECT tablespace_name, leader, replica, expectedreplicacount FROM systablespaces";
    private static final String SELECT_ALL_FROM_TABLESPACE_REPLICA_STATE_TABLE =
            "SELECT tablespace_name, nodeid, mode, inactivitytime, maxleaderinactivitytime FROM systablespacereplicastate";

    /**
     * How long, in milliseconds, a replica may stay silent before {@link #checkWritable()} considers it down, when its
     * tablespace sets no {@code maxleaderinactivitytime}.
     */
    public static final String REPLICA_MAX_INACTIVITY_PROPERTY = "carapace.store.replica.maxinactivity";

    private static final int CHECK_WRITABLE_TIMEOUT_SECONDS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(HerdDBConfigurationStore.class);

    // replaced as a whole, so that readers never see half of a reload
    private volatile Map<String, String> properties = Map.of();
    private volatile String loadedStateGeneration;
    private final HerdDBEmbeddedDataSource datasource;
    private final boolean cluster;
    private final String tableSpace;
    // one thread for every check: a hung read makes the next check time out, and nothing piles up
    private final ExecutorService checkWritableExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("carapace-check-writable").setDaemon(true).build());

    public HerdDBConfigurationStore(ConfigurationStore staticConfiguration,
                                    boolean cluster, String zkAddress, File baseDir, StatsLogger statsLogger) {
        String tableSpace = staticConfiguration.getProperty(TABLESPACE_PROPERTY, TableSpace.DEFAULT).trim();
        this.cluster = cluster;
        this.tableSpace = tableSpace;
        if (!VALID_TABLESPACE_NAME.matcher(tableSpace).matches()) {
            throw new ConfigurationStoreException(new IllegalArgumentException(
                    "Invalid " + TABLESPACE_PROPERTY + " \"" + tableSpace + "\": "
                    + "only letters, digits and underscore are allowed"
            ));
        }
        this.datasource = buildDatasource(staticConfiguration, cluster, zkAddress, baseDir, statsLogger);
        try {
            // HerdDB lowercases the name to look it up, so the default tablespace answers to any spelling
            if (!TableSpace.DEFAULT.equalsIgnoreCase(tableSpace)) {
                // a standalone node cannot replicate, whatever replication.factor says
                ensureTableSpace(tableSpace, cluster ? replicationFactor(staticConfiguration) : 1);
                datasource.setDefaultSchema(tableSpace);
            }
            createTables();
            loadCurrentConfiguration();
        } catch (RuntimeException err) {
            try {
                datasource.close();
            } catch (RuntimeException closeErr) {
                err.addSuppressed(closeErr);
            }
            throw err;
        }
    }

    /**
     * Reads the expected replica count for the cluster.
     *
     * @param staticConfiguration the static configuration
     * @return the value of {@code replication.factor}, 1 by default
     * @throws ConfigurationStoreException if the value is not a number
     */
    private static int replicationFactor(ConfigurationStore staticConfiguration) {
        try {
            return staticConfiguration.getInt("replication.factor", 1);
        } catch (ConfigurationNotValidException err) {
            throw new ConfigurationStoreException(err);
        }
    }

    /**
     * Makes sure that the given tablespace exists and is up, creating it if needed.
     * <br>
     * The connection used here still targets the default tablespace, the only one HerdDB creates on its own.
     *
     * @param tableSpace  the tablespace name, already validated
     * @param replication the expected replica count used when creating the tablespace
     * @throws ConfigurationStoreException if the tablespace cannot be created or does not become available in time
     */
    private void ensureTableSpace(String tableSpace, int replication) {
        try (Connection con = datasource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(CREATE_TABLESPACE.formatted(tableSpace, replication))) {
                ps.executeUpdate();
                LOG.info("Created tablespace {} with expectedreplicacount={}", tableSpace, replication);
            } catch (SQLException err) {
                // it may already be there, from a previous boot or from another node
                boolean exists = false;
                try (PreparedStatement ps = con.prepareStatement(SELECT_FROM_TABLESPACES_TABLE)) {
                    ps.setString(1, tableSpace);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                } catch (SQLException lookupErr) {
                    err.addSuppressed(lookupErr);
                }
                if (!exists) {
                    throw err;
                }
            }
            final HDBConnection hdbConnection = con.unwrap(HerdDBConnection.class).getConnection();
            if (!hdbConnection.waitForTableSpace(tableSpace, TABLESPACE_TIMEOUT)) {
                throw new SQLException("Tablespace " + tableSpace
                        + " not available after " + TABLESPACE_TIMEOUT + " ms");
            }
        } catch (SQLException | HDBException | ClientSideMetadataProviderException err) {
            LOG.error("Error while preparing tablespace {}", tableSpace, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<String, String> consumer) {
        properties.forEach(consumer);
    }

    @Override
    public void forEach(String prefix, BiConsumer<String, String> consumer) {
        properties.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                consumer.accept(k.substring(prefix.length()), v);
            }
        });
    }

    private HerdDBEmbeddedDataSource buildDatasource(ConfigurationStore staticConfiguration,
                                                     boolean cluster, String zkAddress, File baseDir, StatsLogger statsLogger) {
        Properties props = new Properties();

        if (cluster) {
            int replicationFactor = replicationFactor(staticConfiguration);
            props.setProperty(ServerConfiguration.PROPERTY_MODE, ServerConfiguration.PROPERTY_MODE_CLUSTER);
            props.setProperty(ServerConfiguration.PROPERTY_ZOOKEEPER_ADDRESS, zkAddress);
            props.setProperty(ServerConfiguration.PROPERTY_BOOKKEEPER_START, "true");

            String replication = replicationFactor + "";
            props.setProperty(ServerConfiguration.PROPERTY_BOOKKEEPER_ACKQUORUMSIZE, replication);
            props.setProperty(ServerConfiguration.PROPERTY_BOOKKEEPER_ENSEMBLE, replication);
            props.setProperty(ServerConfiguration.PROPERTY_BOOKKEEPER_WRITEQUORUMSIZE, replication);

            props.setProperty(ClientConfiguration.PROPERTY_MODE, ClientConfiguration.PROPERTY_MODE_CLUSTER);
            props.setProperty(ClientConfiguration.PROPERTY_ZOOKEEPER_ADDRESS, zkAddress);
        }

        props.setProperty(ServerConfiguration.PROPERTY_BASEDIR, baseDir.getAbsolutePath());

        // config file can override all of the configuration properties
        props.putAll(staticConfiguration.asProperties("db"));

        LOG.info("HerdDB datasource configuration: {}", props);
        HerdDBEmbeddedDataSource ds = new HerdDBEmbeddedDataSource(props);
        ds.setStatsLogger(statsLogger);
        if (cluster) {
            ds.setWaitForTableSpace(TableSpace.DEFAULT);
            ds.setWaitForTableSpaceTimeout(TABLESPACE_TIMEOUT);
            ds.setStartServer(true);
        }

        // single-admin-user
        String user = props.getProperty(SimpleSingleUserManager.PROPERTY_ADMIN_USERNAME, ClientConfiguration.PROPERTY_CLIENT_USERNAME_DEFAULT);
        String pw = props.getProperty(SimpleSingleUserManager.PROPERTY_ADMIN_PASSWORD, ClientConfiguration.PROPERTY_CLIENT_PASSWORD_DEFAULT);
        ds.setUsername(user);
        ds.setPassword(pw);

        return ds;
    }

    @Override
    public void reload() {
        LOG.info("reloading configuration from Database");
        loadCurrentConfiguration();
    }

    @Override
    public String getLoadedStateGeneration() {
        return loadedStateGeneration;
    }

    @Override
    public String readStateGeneration() {
        try (Connection con = datasource.getConnection()) {
            return readStateGeneration(con);
        } catch (SQLException err) {
            LOG.error("Error while reading the state generation from Database", err);
            throw new ConfigurationStoreException(err);
        }
    }

    private static String readStateGeneration(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_FROM_STATE_GENERATION_TABLE)) {
            ps.setString(1, STATE_GENERATION_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Creates the tables that do not exist yet, once at boot: {@link #reload()} runs before every write.
     *
     * @throws ConfigurationStoreException if no connection is available
     */
    private void createTables() {
        try (Connection con = datasource.getConnection()) {
            List<String> tablesDDL = Arrays.asList(
                    CREATE_CONFIG_TABLE,
                    CREATE_KEYPAIR_TABLE,
                    CREATE_DIGITAL_CERTIFICATES_TABLE,
                    CREATE_ACME_CHALLENGE_TOKENS_TABLE,
                    CREATE_STATE_GENERATION_TABLE
            );
            tablesDDL.forEach((tableDDL) -> {
                try (PreparedStatement ps = con.prepareStatement(tableDDL)) {
                    ps.executeUpdate();
                    LOG.info("Created table {}", tableDDL);
                } catch (SQLException err) {
                    LOG.debug("Could not create table {}", tableDDL, err);
                }
            });
        } catch (SQLException err) {
            LOG.error("Error while creating the tables", err);
            throw new ConfigurationStoreException(err);
        }
    }

    /**
     * Loads the configuration and the state generation into the cache.
     *
     * @throws ConfigurationStoreException if the database cannot be read
     */
    private void loadCurrentConfiguration() {
        Map<String, String> loaded = new HashMap<>();
        try (Connection con = datasource.getConnection()) {
            // read first: a restore landing in between leaves an old generation on the new config, never the reverse
            final String generation = readStateGeneration(con);
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_CONFIG_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loaded.put(rs.getString(1), rs.getString(2));
                }
            }
            properties = Map.copyOf(loaded);
            loadedStateGeneration = generation;
        } catch (SQLException err) {
            LOG.error("Error while loading configuration from Database", err);
            throw new ConfigurationStoreException(err);
        }

    }

    @Override
    public void close() {
        checkWritableExecutor.shutdownNow();
        if (datasource != null) {
            datasource.close();
        }
    }

    @Override
    public void commitConfiguration(ConfigurationStore newConfigurationStore) {
        Set<String> currentKeys = new HashSet<>(this.properties.keySet());
        Map<String, String> newProperties = new HashMap<>();
        try (Connection con = datasource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement psUpdate = con.prepareStatement(UPDATE_CONFIG_TABLE);
                    PreparedStatement psDelete = con.prepareStatement(DELETE_FROM_CONFIG_TABLE);
                    PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_CONFIG_TABLE)) {
                newConfigurationStore.forEach((k, v) -> {
                    try {
                        LOG.info("Saving \"{}\"=\"{}\"", k, v);
                        currentKeys.remove(k);
                        newProperties.put(k, v);
                        psUpdate.setString(1, v);
                        psUpdate.setString(2, k);
                        if (psUpdate.executeUpdate() == 0) {
                            psInsert.setString(1, k);
                            psInsert.setString(2, v);
                            psInsert.executeUpdate();
                        }
                    } catch (SQLException err) {
                        throw new ConfigurationStoreException(err);
                    }
                });
                currentKeys.forEach(k -> {
                    try {
                        LOG.info("Deleting \"{}\"", k);
                        psDelete.setString(1, k);
                        psDelete.executeUpdate();
                    } catch (SQLException err) {
                        throw new ConfigurationStoreException(err);
                    }
                });
            }
            con.commit();

            // Local cached properties updating
            properties = Map.copyOf(newProperties);
        } catch (SQLException err) {
            LOG.error("Error while saving configuration from Database", err);
            throw new ConfigurationStoreException(err);
        } catch (ConfigurationStoreException err) {
            LOG.error("Error while saving configuration from Database", err);
            throw err;
        }
    }

    @Override
    public StoreSnapshot exportState() {
        final Map<String, String> config = new HashMap<>();
        final Map<String, StoreSnapshot.EncodedKeyPair> keyPairs = new HashMap<>();
        final Map<String, StoreSnapshot.EncodedKeyPair> acmeAccountKeys = new HashMap<>();
        final Map<String, StoreSnapshot.CertificateRow> certificates = new HashMap<>();
        try (Connection con = datasource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_CONFIG_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    config.put(rs.getString(1), rs.getString(2));
                }
            }
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_KEYPAIR_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final var domain = rs.getString(1);
                    final var pair = new StoreSnapshot.EncodedKeyPair(rs.getString(2), rs.getString(3));
                    if (ACME_USER_KEY.equals(domain)) {
                        acmeAccountKeys.put(StoreSnapshot.LETSENCRYPT, pair);
                    } else {
                        keyPairs.put(domain, pair);
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_DIGITAL_CERTIFICATES_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    certificates.put(rs.getString(1), readCertificateRow(rs));
                }
            }
        } catch (SQLException err) {
            LOG.error("Error while exporting the state from Database", err);
            throw new ConfigurationStoreException(err);
        }
        return new StoreSnapshot(config, keyPairs, acmeAccountKeys, certificates);
    }

    @Override
    public void replaceState(StoreSnapshot snapshot, Runnable preCommitCheck) {
        // built before the commit, so that nothing after it can throw (Map.copyOf refuses null values)
        final Map<String, String> newProperties = Map.copyOf(snapshot.config());
        final String newGeneration = UUID.randomUUID().toString();
        try (Connection con = datasource.getConnection()) {
            con.setAutoCommit(false);
            try {
                // DELETE without WHERE: TRUNCATE is refused inside a transaction; the ACME tokens table is left alone
                for (String table : List.of(CONFIG_TABLE_NAME, KEYPAIR_TABLE_NAME, DIGITAL_CERTIFICATES_TABLE_NAME, STATE_GENERATION_TABLE_NAME)) {
                    try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table)) {
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(INSERT_INTO_CONFIG_TABLE)) {
                    for (var entry : snapshot.config().entrySet()) {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = con.prepareStatement(INSERT_INTO_KEYPAIR_TABLE)) {
                    for (var entry : snapshot.keyPairs().entrySet()) {
                        addKeyPair(ps, entry.getKey(), entry.getValue());
                    }
                    final var acmeKey = snapshot.acmeAccountKeys().get(StoreSnapshot.LETSENCRYPT);
                    if (acmeKey != null) {
                        addKeyPair(ps, ACME_USER_KEY, acmeKey);
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = con.prepareStatement(INSERT_INTO_DIGITAL_CERTIFICATES_TABLE)) {
                    for (var entry : snapshot.certificates().entrySet()) {
                        bindCertificate(ps, entry.getKey(), entry.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = con.prepareStatement(INSERT_INTO_STATE_GENERATION_TABLE)) {
                    ps.setString(1, newGeneration);
                    ps.setString(2, STATE_GENERATION_ID);
                    ps.executeUpdate();
                }
                preCommitCheck.run();
                con.commit();
            } catch (SQLException | RuntimeException err) {
                try {
                    con.rollback();
                } catch (SQLException rollbackErr) {
                    err.addSuppressed(rollbackErr);
                }
                throw err;
            }
        } catch (SQLException err) {
            LOG.error("Error while replacing the state in Database", err);
            throw new ConfigurationStoreException(err);
        }
        properties = newProperties;
        loadedStateGeneration = newGeneration;
    }

    /**
     * In cluster mode, refuses when a replica of the tablespace is missing, stopped or silent for longer than
     * HerdDB tolerates from a leader ({@value #REPLICA_MAX_INACTIVITY_PROPERTY} when the tablespace does not say),
     * or when the tablespace does not answer within {@value #CHECK_WRITABLE_TIMEOUT_SECONDS} seconds: a write would
     * then hang on the BookKeeper quorum or on a missing leader. A standalone node is never refused.
     *
     * @throws ConfigurationNotValidException listing every problem found, or telling why the replica state cannot be read
     */
    @Override
    public void checkWritable() throws ConfigurationNotValidException {
        if (!cluster) {
            return;
        }
        final List<String> problems = readReplicaProblemsWithin();
        if (!problems.isEmpty()) {
            throw new ConfigurationNotValidException("The database cannot take the write safely now: " + String.join("; ", problems));
        }
    }

    /**
     * In cluster mode, refuses when the tablespace does not answer within {@value #CHECK_WRITABLE_TIMEOUT_SECONDS}
     * seconds, as when its leader is down. Silent replicas are not checked: reads and plain writes do not need them.
     *
     * @throws ConfigurationNotValidException if the replica state cannot be read in time
     */
    @Override
    public void checkAnswering() throws ConfigurationNotValidException {
        if (cluster) {
            readReplicaProblemsWithin();
        }
    }

    /**
     * Reads the replica state aside, giving up after {@value #CHECK_WRITABLE_TIMEOUT_SECONDS} seconds.
     *
     * @return the problems found, empty if every expected replica is up
     * @throws ConfigurationNotValidException if the replica state cannot be read in time
     */
    private List<String> readReplicaProblemsWithin() throws ConfigurationNotValidException {
        // the client retries an unreachable leader for minutes: run the reads aside and give up early
        final Future<List<String>> read = checkWritableExecutor.submit(this::readReplicaProblems);
        final List<String> problems;
        try {
            problems = read.get(CHECK_WRITABLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException err) {
            read.cancel(true);
            throw new ConfigurationNotValidException("Tablespace " + tableSpace + " did not answer within "
                    + CHECK_WRITABLE_TIMEOUT_SECONDS + " s: is its leader down?");
        } catch (InterruptedException err) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw new ConfigurationNotValidException("Interrupted while reading the replica state of tablespace " + tableSpace);
        } catch (ExecutionException err) {
            if (err.getCause() instanceof ConfigurationNotValidException notValid) {
                throw notValid;
            }
            throw new ConfigurationNotValidException("Cannot read the replica state of tablespace " + tableSpace + ": " + err.getCause());
        }
        return problems;
    }

    /**
     * Reads the replica state of the tablespace from the HerdDB system tables.
     *
     * @return the problems found, empty if every expected replica is up
     * @throws SQLException                   if the system tables cannot be read
     * @throws ConfigurationNotValidException if the tablespace is not found
     */
    private List<String> readReplicaProblems() throws SQLException, ConfigurationNotValidException {
        final List<String> problems = new ArrayList<>();
        try (Connection con = datasource.getConnection()) {
            String leader = null;
            Set<String> replicas = null;
            int expectedReplicas = 0;
            // filtered here: HerdDB matches tablespace names ignoring case
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_TABLESPACES_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (tableSpace.equalsIgnoreCase(rs.getString(1))) {
                        leader = rs.getString(2);
                        final String replicaList = rs.getString(3);
                        replicas = Stream.of(replicaList == null ? new String[0] : replicaList.split(","))
                                .map(String::trim)
                                .filter(replica -> !replica.isEmpty())
                                .collect(Collectors.toCollection(TreeSet::new));
                        expectedReplicas = rs.getInt(4);
                    }
                }
            }
            if (replicas == null) {
                throw new ConfigurationNotValidException("Tablespace " + tableSpace + " not found in systablespaces");
            }
            if (leader == null) {
                problems.add("tablespace " + tableSpace + " has no leader");
            }
            if (replicas.size() < expectedReplicas) {
                problems.add("tablespace " + tableSpace + " has " + replicas.size() + " replicas " + replicas
                        + ", " + expectedReplicas + " expected");
            }
            final Map<String, String> replicaProblems = new TreeMap<>();
            replicas.forEach(replica -> replicaProblems.put(replica, "replica " + replica + " has no state"));
            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_TABLESPACE_REPLICA_STATE_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String node = rs.getString(2);
                    if (!tableSpace.equalsIgnoreCase(rs.getString(1)) || !replicas.contains(node)) {
                        continue; // the table also keeps former replicas and the other tablespaces
                    }
                    final String mode = rs.getString(3);
                    final long inactivity = rs.getLong(4);
                    // 0 means disabled, the default of the tablespaces Carapace creates: the activator pings about every second
                    final long maxInactivity = rs.getLong(5) > 0 ? rs.getLong(5) : Long.getLong(REPLICA_MAX_INACTIVITY_PROPERTY, 30_000);
                    if (!"leader".equals(mode) && !"follower".equals(mode)) {
                        replicaProblems.put(node, "replica " + node + " is " + mode);
                    } else if (inactivity > maxInactivity) {
                        replicaProblems.put(node, "replica " + node + " silent for " + inactivity + " ms, more than " + maxInactivity + " ms");
                    } else {
                        replicaProblems.remove(node);
                    }
                }
            }
            problems.addAll(replicaProblems.values());
        }
        return problems;
    }

    private static void addKeyPair(PreparedStatement ps, String domain, StoreSnapshot.EncodedKeyPair pair) throws SQLException {
        ps.setString(1, domain);
        ps.setString(2, pair.privateKey());
        ps.setString(3, pair.publicKey());
        ps.addBatch();
    }

    @Override
    public KeyPair loadAcmeUserKeyPair() {
        try {
            return loadKeyPair(ACME_USER_KEY);
        } catch (Exception err) {
            LOG.error("Error while performing KeyPair loading for ACME user.", err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public boolean saveAcmeUserKey(KeyPair pair) {
        try {
            return saveKeyPair(pair, ACME_USER_KEY, false);
        } catch (Exception err) {
            LOG.error("Error while performing KeyPar saving for ACME user.", err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public KeyPair loadKeyPairForDomain(String domain) {
        try {
            if (domain.equals(ACME_USER_KEY)) {
                return null;
            }
            return loadKeyPair(domain);
        } catch (Exception err) {
            LOG.error("Error while performing KeyPair loading for domain {}.", domain, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public boolean saveKeyPairForDomain(KeyPair pair, String domain, boolean update) {
        try {
            if (!domain.equals(ACME_USER_KEY)) {
                return saveKeyPair(pair, domain, update);
            }
        } catch (Exception err) {
            LOG.error("Error while performing KeyPar saving for domain {}.", domain, err);
            throw new ConfigurationStoreException(err);
        }
        return false;
    }

    private KeyPair loadKeyPair(String pk) throws Exception {
        try (Connection con = datasource.getConnection(); PreparedStatement ps = con.prepareStatement(SELECT_FROM_KEYPAIR_TABLE)) {
            ps.setString(1, pk);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PrivateKey privateKey = base64DecodePrivateKey(rs.getString(1));
                    PublicKey publicKey = base64DecodePublicKey(rs.getString(2));
                    return new KeyPair(publicKey, privateKey);
                }
            }
            return null;
        }
    }

    private boolean saveKeyPair(KeyPair pair, String pk, boolean update) {
        try (Connection con = datasource.getConnection(); PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_KEYPAIR_TABLE); PreparedStatement psUpdate = con.prepareStatement(
                UPDATE_KEYPAIR_TABLE)) {
            String privateKey = base64EncodeKey(pair.getPrivate());
            String publicKey = base64EncodeKey(pair.getPublic());
            boolean updateDone = false;
            if (update) {
                psUpdate.setString(1, privateKey);
                psUpdate.setString(2, publicKey);
                psUpdate.setString(3, pk);
                updateDone = psUpdate.executeUpdate() > 0;
            }
            if (!updateDone) {
                psInsert.setString(1, pk);
                psInsert.setString(2, privateKey);
                psInsert.setString(3, publicKey);
                return psInsert.executeUpdate() > 0;
            }
            return updateDone;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public CertificateData loadCertificateForDomain(String domain) {
        if (domain.equals(ACME_USER_KEY)) {
            return null;
        }
        try (Connection con = datasource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(SELECT_FROM_DIGITAL_CERTIFICATES_TABLE)) {
                ps.setString(1, domain);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return toCertificateData(domain, readCertificateRow(rs));
                    }
                }
                return null;
            }
        } catch (Exception err) {
            LOG.error("Error while performing Certificate loading for domain {}.", domain, err);
            throw new ConfigurationStoreException(err);
        }
    }

    /**
     * Reads a row selected with the columns of {@link #SELECT_ALL_FROM_DIGITAL_CERTIFICATES_TABLE}.
     *
     * @param rs the result set, positioned on the row
     * @return the row, values as stored
     * @throws SQLException if a column cannot be read
     */
    private static StoreSnapshot.CertificateRow readCertificateRow(ResultSet rs) throws SQLException {
        final var subjectAltNames = rs.getString(2);
        return new StoreSnapshot.CertificateRow(
                StringUtils.isBlank(subjectAltNames)
                        ? List.of()
                        : Arrays.stream(subjectAltNames.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getInt(7),
                rs.getString(8)
        );
    }

    /**
     * Binds a certificate row to {@link #INSERT_INTO_DIGITAL_CERTIFICATES_TABLE} or
     * {@link #UPDATE_DIGITAL_CERTIFICATES_TABLE}, which take the same parameters in the same order.
     *
     * @param ps     the statement
     * @param domain the certificate domain
     * @param row    the certificate row
     * @throws SQLException if a parameter cannot be set
     */
    private static void bindCertificate(PreparedStatement ps, String domain, StoreSnapshot.CertificateRow row) throws SQLException {
        ps.setString(1, row.subjectAltNames().isEmpty() ? null : String.join(",", row.subjectAltNames()));
        ps.setString(2, row.chain());
        ps.setString(3, row.state());
        ps.setString(4, row.pendingOrder());
        ps.setString(5, row.pendingChallenges());
        ps.setInt(6, row.attemptCount());
        ps.setString(7, row.message());
        ps.setString(8, domain);
    }

    @Override
    public void saveCertificate(CertificateData cert) {
        try (Connection con = datasource.getConnection();
                PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_DIGITAL_CERTIFICATES_TABLE);
                PreparedStatement psUpdate = con.prepareStatement(UPDATE_DIGITAL_CERTIFICATES_TABLE)) {
            final var row = toCertificateRow(cert);
            bindCertificate(psUpdate, cert.getDomain(), row);
            if (psUpdate.executeUpdate() == 0) {
                bindCertificate(psInsert, cert.getDomain(), row);
                psInsert.executeUpdate();
            }

        } catch (Exception err) {
            LOG.error("Error while performing Certificate saving for domain {}.", cert.getDomain(), err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public void removeCertificate(final String certId) {
        try (final var connection = datasource.getConnection();
             final var preparedStatement = connection.prepareStatement(REMOVE_DIGITAL_CERTIFICATES_TABLE)) {
            preparedStatement.setString(1, certId);
            preparedStatement.executeUpdate();
        } catch (final SQLException err) {
            LOG.error("Error while performing Certificate drop for domain {}.", certId, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public void saveAcmeChallengeToken(String id, String data) {
        try (Connection con = datasource.getConnection();
                PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_ACME_CHALLENGE_TOKENS_TABLE)) {
            psInsert.setString(1, id);
            psInsert.setString(2, data);
            psInsert.executeUpdate();
        } catch (Exception err) {
            LOG.error("Error while performing saving of ACME challenge token with id: {} data: {}", id, data, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public String loadAcmeChallengeToken(String id) {
        try (Connection con = datasource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(SELECT_FROM_ACME_CHALLENGE_TOKENS_TABLE)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
                return null;
            }
        } catch (Exception err) {
            LOG.error("Error while performing loading of ACME challenge token with id: {}", id, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public void deleteAcmeChallengeToken(String id) {
        try (Connection con = datasource.getConnection();
                PreparedStatement psDelete = con.prepareStatement(DELETE_FROM_ACME_CHALLENGE_TOKENS_TABLE)) {
            LOG.info("Deleting ACME challenge token with id \"{}\"", id);
            psDelete.setString(1, id);
            psDelete.executeUpdate();
        } catch (SQLException err) {
            LOG.error("Error while performing deleting of ACME challenge token with id: {}", id, err);
            throw new ConfigurationStoreException(err);
        }
    }

}
