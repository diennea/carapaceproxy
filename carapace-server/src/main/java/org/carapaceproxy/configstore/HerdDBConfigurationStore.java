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
package org.carapaceproxy.configstore;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import herddb.client.ClientConfiguration;
import herddb.jdbc.HerdDBEmbeddedDataSource;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.bookkeeper.stats.StatsLogger;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePrivateKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePublicKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeKey;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import sun.net.www.content.text.plain;

/**
 * Reads/Write the configuration to a JDBC database. This configuration store is able to track versions of configuration
 * properties
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "https://github.com/spotbugs/spotbugs/issues/432")
public class HerdDBConfigurationStore implements ConfigurationStore {

    private static final int TIMEOUT_WAIT_FOR_TABLE_SPACE = Integer.getInteger("herd.waitfortablespace.timeout", 1000 * 60 * 5); // default 5 min
    
    public static final String ACME_USER_KEY = "_acmeuserkey";

    // Main table
    private static final String CONFIG_TABLE_NAME = "proxy_config";
    private static final String CREATE_CONFIG_TABLE = "CREATE TABLE " + CONFIG_TABLE_NAME + "(pname string primary key, pvalue string)";
    private static final String SELECT_ALL_FROM_CONFIG_TABLE = "SELECT pname,pvalue from " + CONFIG_TABLE_NAME;
    private static final String UPDATE_CONFIG_TABLE = "UPDATE " + CONFIG_TABLE_NAME + " set pvalue=? WHERE pname=?";
    private static final String DELETE_FROM_CONFIG_TABLE = "DELETE FROM " + CONFIG_TABLE_NAME + " WHERE pname=?";
    private static final String INSERT_INTO_CONFIG_TABLE = "INSERT INTO " + CONFIG_TABLE_NAME + "(pname,pvalue) values (?,?)";

    // Table for KeyPairs
    private static final String KEYPAIR_TABLE_NAME = "keypairs";
    private static final String CREATE_KEYPAIR_TABLE = "CREATE TABLE " + KEYPAIR_TABLE_NAME
            + "(domain string primary key, privateKey string, publicKey string)";
    private static final String SELECT_FROM_KEYPAIR_TABLE = "SELECT privateKey, publicKey FROM " + KEYPAIR_TABLE_NAME
            + " WHERE domain=?";
    private static final String UPDATE_KEYPAIR_TABLE = "UPDATE " + KEYPAIR_TABLE_NAME
            + " SET privateKey=?, publicKey=? WHERE domain=?";
    private static final String INSERT_INTO_KEYPAIR_TABLE = "INSERT INTO " + KEYPAIR_TABLE_NAME
            + "(domain, privateKey, publicKey) values (?, ?, ?)";

    // Table for ACME Certificates
    private static final String DIGITAL_CERTIFICATES_TABLE_NAME = "digital_certificates";
    private static final String CREATE_DIGITAL_CERTIFICATES_TABLE = "CREATE TABLE " + DIGITAL_CERTIFICATES_TABLE_NAME
            + "(domain string primary key, privateKey string, chain string, "
            + "state string, pendingOrder string, pendingChallenge string, available tinyint)";
    private static final String SELECT_FROM_DIGITAL_CERTIFICATES_TABLE = "SELECT * from " + DIGITAL_CERTIFICATES_TABLE_NAME + " WHERE domain=?";
    private static final String UPDATE_DIGITAL_CERTIFICATES_TABLE = "UPDATE " + DIGITAL_CERTIFICATES_TABLE_NAME
            + " SET privateKey=?, chain=?, state=?, pendingOrder=?, pendingChallenge=?, available=? WHERE domain=?";
    private static final String INSERT_INTO_DIGITAL_CERTIFICATES_TABLE = "INSERT INTO " + DIGITAL_CERTIFICATES_TABLE_NAME
            + "(domain, privateKey, chain, state, pendingOrder, pendingChallenge, available) values (?, ?, ?, ?, ?, ?, ?)";

    // Table for ACME challenge tokens
    private static final String ACME_CHALLENGE_TOKENS_TABLE_NAME = "acme_challenge_tokens";
    private static final String CREATE_ACME_CHALLENGE_TOKENS_TABLE = "CREATE TABLE " + ACME_CHALLENGE_TOKENS_TABLE_NAME
            + "(id string primary key, data string)";
    private static final String SELECT_FROM_ACME_CHALLENGE_TOKENS_TABLE = "SELECT data from " + ACME_CHALLENGE_TOKENS_TABLE_NAME + " WHERE id=?";
    private static final String INSERT_INTO_ACME_CHALLENGE_TOKENS_TABLE = "INSERT INTO " + ACME_CHALLENGE_TOKENS_TABLE_NAME
            + "(id, data) values (?, ?)";
    private static final String DELETE_FROM_ACME_CHALLENGE_TOKENS_TABLE = "DELETE from " + ACME_CHALLENGE_TOKENS_TABLE_NAME
            + " WHERE id=?";

    private static final Logger LOG = Logger.getLogger(HerdDBConfigurationStore.class.getName());

    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private final HerdDBEmbeddedDataSource datasource;

    public HerdDBConfigurationStore(ConfigurationStore staticConfiguration,
            boolean cluster, String zkAddress, File baseDir, StatsLogger statsLogger) throws ConfigurationNotValidException {
        this.datasource = buildDatasource(staticConfiguration, cluster, zkAddress, baseDir, statsLogger);
        loadCurrentConfiguration();
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<String, String> consumer) {
        properties.forEach((k, v) -> {
            consumer.accept(k, v);
        });
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
            int replicationFactor = Integer.parseInt(staticConfiguration.getProperty("replication.factor", "1"));
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

        LOG.log(Level.INFO, "HerdDB datasource configuration: {0}", props);
        HerdDBEmbeddedDataSource ds = new HerdDBEmbeddedDataSource(props);
        ds.setStatsLogger(statsLogger);
        if (cluster) {
            ds.setWaitForTableSpace("herd");
            ds.setWaitForTableSpaceTimeout(TIMEOUT_WAIT_FOR_TABLE_SPACE);
            ds.setStartServer(true);
        }
        return ds;
    }

    @Override
    public void reload() {
        LOG.log(Level.INFO, "reloading configuration from Database");
        Set<String> currentKeys = new HashSet<>(this.properties.keySet());

        Set<String> loaded = loadCurrentConfiguration();
        currentKeys.forEach(name -> {
            if (!loaded.contains(name)) {
                properties.remove(name);
            }
        });
    }

    private Set<String> loadCurrentConfiguration() {
        Set<String> loaded = new HashSet<>();
        try (Connection con = datasource.getConnection();) {
            List<String> tablesDDL = Arrays.asList(
                    CREATE_CONFIG_TABLE,
                    CREATE_KEYPAIR_TABLE,
                    CREATE_DIGITAL_CERTIFICATES_TABLE,
                    CREATE_ACME_CHALLENGE_TOKENS_TABLE
            );
            tablesDDL.forEach((tableDDL) -> {
                try (PreparedStatement ps = con.prepareStatement(tableDDL);) {
                    ps.executeUpdate();
                    LOG.log(Level.INFO, "Created table " + tableDDL);
                } catch (SQLException err) {
                    LOG.log(Level.FINE, "Could not create table " + tableDDL, err);
                }
            });

            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL_FROM_CONFIG_TABLE); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pname = rs.getString(1);
                    String pvalue = rs.getString(2);
                    properties.put(pname, pvalue);
                    loaded.add(pname);
                }
            }
            return loaded;
        } catch (SQLException err) {
            LOG.log(Level.SEVERE, "Error while loading configuration from Database", err);
            throw new ConfigurationStoreException(err);
        }

    }

    @Override
    public void close() {
        if (datasource != null) {
            datasource.close();
        }
    }

    @Override
    public void commitConfiguration(ConfigurationStore newConfigurationStore) {
        Set<String> currentKeys = new HashSet<>(this.properties.keySet());
        Map<String, String> newProperties = new HashMap();
        try (Connection con = datasource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement psUpdate = con.prepareStatement(UPDATE_CONFIG_TABLE);
                    PreparedStatement psDelete = con.prepareStatement(DELETE_FROM_CONFIG_TABLE);
                    PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_CONFIG_TABLE)) {
                newConfigurationStore.forEach((k, v) -> {
                    try {
                        LOG.log(Level.INFO, "Saving '" + k + "'='" + v + "'");
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
                        LOG.log(Level.INFO, "Deleting '" + k + "'");
                        psDelete.setString(1, k);
                        psDelete.executeUpdate();
                    } catch (SQLException err) {
                        throw new ConfigurationStoreException(err);
                    }
                });
            }
            con.commit();

            // Local cached properties updating
            currentKeys.forEach(k -> {
                properties.remove(k);
            });
            properties.putAll(newProperties);
        } catch (SQLException err) {
            LOG.log(Level.SEVERE, "Error while saving configuration from Database", err);
            throw new ConfigurationStoreException(err);
        } catch (ConfigurationStoreException err) {
            LOG.log(Level.SEVERE, "Error while saving configuration from Database", err);
            throw err;
        }
    }

    @Override
    public KeyPair loadAcmeUserKeyPair() {
        try {
            return loadKeyPair(ACME_USER_KEY);
        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing KeyPair loading for ACME user.", err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public boolean saveAcmeUserKey(KeyPair pair) {
        try {
            return saveKeyPair(pair, ACME_USER_KEY, false);
        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing KeyPar saving for ACME user.", err);
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
            LOG.log(Level.SEVERE, "Error while performing KeyPair loading for domain " + domain + ".", err);
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
            LOG.log(Level.SEVERE, "Error while performing KeyPar saving for domain " + domain + ".", err);
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
        try (Connection con = datasource.getConnection(); PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_KEYPAIR_TABLE); PreparedStatement psUpdate = con.prepareStatement(UPDATE_KEYPAIR_TABLE)) {
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
        } catch (SQLException e ) {
            return false;
        }
    }

    @Override
    public CertificateData loadCertificateForDomain(String domain) {
        if (domain.equals(ACME_USER_KEY)) {
            return null;
        }
        try (Connection con = datasource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(SELECT_FROM_DIGITAL_CERTIFICATES_TABLE);) {
                ps.setString(1, domain);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String privateKey = rs.getString(2);
                        String chain = rs.getString(3);
                        String state = rs.getString(4);
                        String pendingOrder = rs.getString(5);
                        String pendigChallenge = rs.getString(6);
                        boolean available = rs.getInt(7) == 1;
                        return new CertificateData(domain, privateKey, chain, state, pendingOrder, pendigChallenge, available);
                    }
                }
                return null;
            }
        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing Certificate loading for domain " + domain + ".", err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public void saveCertificate(CertificateData cert) {
        try (Connection con = datasource.getConnection();
                PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_DIGITAL_CERTIFICATES_TABLE);
                PreparedStatement psUpdate = con.prepareStatement(UPDATE_DIGITAL_CERTIFICATES_TABLE)) {
            String domain = cert.getDomain();
            String privateKey = cert.getPrivateKey();
            String chain = cert.getChain();
            String state = cert.getState();
            String pendingOrder = cert.getPendingOrderLocation();
            String pendigChallenge = cert.getPendingChallengeData();
            int available = cert.isAvailable() ? 1 : 0;

            psUpdate.setString(1, privateKey);
            psUpdate.setString(2, chain);
            psUpdate.setString(3, state);
            psUpdate.setString(4, pendingOrder);
            psUpdate.setString(5, pendigChallenge);
            psUpdate.setInt(6, available);
            psUpdate.setString(7, domain);
            if (psUpdate.executeUpdate() == 0) {
                psInsert.setString(1, domain);
                psInsert.setString(2, privateKey);
                psInsert.setString(3, chain);
                psInsert.setString(4, state);
                psInsert.setString(5, pendingOrder);
                psInsert.setString(6, pendigChallenge);
                psInsert.setInt(7, available);
                psInsert.executeUpdate();
            }

        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing Certificate saving for domain " + cert.getDomain() + ".", err);
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
            LOG.log(Level.SEVERE, "Error while performing saving of ACME challenge token with id: " + id + " data: " + data, err);
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
            LOG.log(Level.SEVERE, "Error while performing loading of ACME challenge token with id: " + id, err);
            throw new ConfigurationStoreException(err);
        }
    }

    @Override
    public void deleteAcmeChallengeToken(String id) {
        try (Connection con = datasource.getConnection();
                PreparedStatement psDelete = con.prepareStatement(DELETE_FROM_ACME_CHALLENGE_TOKENS_TABLE)) {
            LOG.log(Level.INFO, "Deleting ACME challenge token with id'" + id + "'");
            psDelete.setString(1, id);
            psDelete.executeUpdate();
        } catch (SQLException err) {
            LOG.log(Level.SEVERE, "Error while performing deleting of ACME challenge token with id: " + id, err);
            throw new ConfigurationStoreException(err);
        }
    }

}
