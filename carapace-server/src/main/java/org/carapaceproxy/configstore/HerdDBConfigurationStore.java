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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

/**
 * Reads/Write the configuration to a JDBC database. This configuration store is
 * able to track versions of configuration properties
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "https://github.com/spotbugs/spotbugs/issues/432")
public class HerdDBConfigurationStore implements ConfigurationStore {

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
            + "(domain string primary key, privateKey string, chain string, available tinyint)";
    private static final String SELECT_FROM_DIGITAL_CERTIFICATES_TABLE = "SELECT * from " + DIGITAL_CERTIFICATES_TABLE_NAME + " WHERE domain=?";
    private static final String UPDATE_DIGITAL_CERTIFICATES_TABLE = "UPDATE " + DIGITAL_CERTIFICATES_TABLE_NAME
            + " SET privateKey=?, chain=?, available=? WHERE domain=?";
    private static final String INSERT_INTO_DIGITAL_CERTIFICATES_TABLE = "INSERT INTO " + DIGITAL_CERTIFICATES_TABLE_NAME
            + "(domain, privateKey, chain, available) values (?, ?, ?, ?)";

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

        LOG.log(Level.INFO, "HerdDB datasource configuration: " + props);
        HerdDBEmbeddedDataSource ds = new HerdDBEmbeddedDataSource(props);
        ds.setStatsLogger(statsLogger);
        if (cluster) {
            ds.setStartServer(true);
        }
        return ds;
    }

    private void loadCurrentConfiguration() {
        try (Connection con = datasource.getConnection();) {
            List<String> tablesDDL = Arrays.asList(CREATE_CONFIG_TABLE, CREATE_KEYPAIR_TABLE, CREATE_DIGITAL_CERTIFICATES_TABLE);
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
                }
            }
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
            try (PreparedStatement psUpdate = con.prepareStatement(UPDATE_CONFIG_TABLE); PreparedStatement psDelete = con.prepareStatement(DELETE_FROM_CONFIG_TABLE); PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_CONFIG_TABLE);) {
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
    public void saveAcmeUserKey(KeyPair pair) {
        try {
            saveKeyPair(pair, ACME_USER_KEY);
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
    public void saveKeyPairForDomain(KeyPair pair, String domain) {
        try {
            if (!domain.equals(ACME_USER_KEY)) {
                saveKeyPair(pair, domain);
            }
        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing KeyPar saving for domain " + domain + ".", err);
            throw new ConfigurationStoreException(err);
        }
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

    private void saveKeyPair(KeyPair pair, String pk) throws Exception {
        try (Connection con = datasource.getConnection(); PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_KEYPAIR_TABLE); PreparedStatement psUpdate = con.prepareStatement(UPDATE_KEYPAIR_TABLE)) {
            String privateKey = base64EncodeKey(pair.getPrivate());
            String publicKey = base64EncodeKey(pair.getPublic());
            psUpdate.setString(1, privateKey);
            psUpdate.setString(2, publicKey);
            psUpdate.setString(3, pk);
            if (psUpdate.executeUpdate() == 0) {
                psInsert.setString(1, pk);
                psInsert.setString(2, privateKey);
                psInsert.setString(3, publicKey);
                psInsert.executeUpdate();
            }
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
                        boolean available = rs.getInt(4) == 1;
                        return new CertificateData(domain, privateKey, chain, available);
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
        try (Connection con = datasource.getConnection(); PreparedStatement psInsert = con.prepareStatement(INSERT_INTO_DIGITAL_CERTIFICATES_TABLE); PreparedStatement psUpdate = con.prepareStatement(UPDATE_DIGITAL_CERTIFICATES_TABLE)) {
            String domain = cert.getDomain();
            String privateKey = cert.getPrivateKey();
            String chain = cert.getChain();
            int available = cert.isAvailable() ? 1 : 0;

            psUpdate.setString(1, privateKey);
            psUpdate.setString(2, chain);
            psUpdate.setInt(3, available);
            psUpdate.setString(4, domain);
            if (psUpdate.executeUpdate() == 0) {
                psInsert.setString(1, domain);
                psInsert.setString(2, privateKey);
                psInsert.setString(3, chain);
                psInsert.setInt(4, available);
                psInsert.executeUpdate();
            }

        } catch (Exception err) {
            LOG.log(Level.SEVERE, "Error while performing Certificate saving for domain " + cert.getDomain() + ".", err);
            throw new ConfigurationStoreException(err);
        }
    }

}
