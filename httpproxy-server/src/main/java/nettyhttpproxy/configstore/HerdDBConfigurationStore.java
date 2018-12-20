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
package nettyhttpproxy.configstore;

import herddb.jdbc.HerdDBEmbeddedDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.server.config.ConfigurationNotValidException;

/**
 * Reads/Write the configuration to a JDBC database. This configuration store is
 * able to track versions of configuration properties
 *
 * @author enrico.olivelli
 */
public class HerdDBConfigurationStore implements ConfigurationStore {

    private static final String TABLE_NAME = "proxy_config";
    private static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + "(pname nvarchar(2000) primary key, pvalue nvarchar(2000))";
    private static final String SELECT_ALL = "SELECT pname,pvalue from " + TABLE_NAME;
    private static final String UPDATE = "UPDATE " + TABLE_NAME + " set pvalue=? WHERE pname=?";
    private static final String DELETE = "DELETE FROM " + TABLE_NAME + " WHERE pname=?";
    private static final String INSERT = "INSERT INTO " + TABLE_NAME + "(pname,pvalue) values (?,?)";
    private static final Logger LOG = Logger.getLogger(HerdDBConfigurationStore.class.getName());

    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private final HerdDBEmbeddedDataSource datasource;

    public HerdDBConfigurationStore(ConfigurationStore staticConfiguration) throws ConfigurationNotValidException {
        this.datasource = buildDatasource(staticConfiguration);
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

    private HerdDBEmbeddedDataSource buildDatasource(ConfigurationStore staticConfiguration) {
        return new HerdDBEmbeddedDataSource(staticConfiguration.asProperties("db"));
    }

    private void loadCurrentConfiguration() {
        try (Connection con = datasource.getConnection();) {
            try (PreparedStatement ps = con.prepareStatement(CREATE_TABLE);) {
                ps.executeUpdate();
                LOG.log(Level.INFO, "Created table " + TABLE_NAME);
            } catch (SQLException err) {
                LOG.log(Level.FINE, "Could not create table " + TABLE_NAME, err);
            }

            try (PreparedStatement ps = con.prepareStatement(SELECT_ALL);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pname = rs.getString(1);
                    String pvalue = rs.getString(2);
                    properties.put(pname, pvalue);
                }
            }
        } catch (SQLException err) {
            LOG.log(Level.SEVERE, "Error while loading configuration from Database", err);
            throw new RuntimeException(err);
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
        try (Connection con = datasource.getConnection();) {
            con.setAutoCommit(false);
            try (PreparedStatement psUpdate = con.prepareStatement(UPDATE);
                    PreparedStatement psDelete = con.prepareStatement(DELETE);
                    PreparedStatement psInsert = con.prepareStatement(INSERT);) {
                newConfigurationStore.forEach((k, v) -> {
                    try {
                        LOG.log(Level.INFO, "Saving '" + k + "'='" + v + "'");
                        currentKeys.remove(k);
                        psUpdate.setString(1, v);
                        psUpdate.setString(2, k);
                        if (psUpdate.executeUpdate() == 0) {
                            psInsert.setString(1, k);
                            psInsert.setString(2, v);
                            psInsert.executeUpdate();
                        }
                    } catch (SQLException err) {
                        throw new RuntimeException(err);
                    }
                });
                currentKeys.forEach(k -> {
                    try {
                        LOG.log(Level.INFO, "Deleting '" + k+"'");
                        psDelete.setString(1, k);
                        psDelete.executeUpdate();
                    } catch (SQLException err) {
                        throw new RuntimeException(err);
                    }
                });
            }
            con.commit();
        } catch (SQLException err) {
            LOG.log(Level.SEVERE, "Error while saving configuration from Database", err);
            throw new RuntimeException(err);
        } catch (RuntimeException err) {
            LOG.log(Level.SEVERE, "Error while savingconfiguration from Database", err);
            throw err;
        }
    }

}
