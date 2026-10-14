package org.carapaceproxy;

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
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.carapaceproxy.core.HttpProxyServer;
import java.util.Properties;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.HerdDBConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.powermock.reflect.Whitebox;

/**
 *
 * @author enrico.olivelli
 */
public class DatabaseConfigurationTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testBootWithDatabaseStore() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            Properties configuration = new Properties();

            configuration.put("config.type", "database");
            configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
            configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));

            server.start();
            assertThat(server.getDynamicConfigurationStore(), instanceOf(HerdDBConfigurationStore.class));

        }

    }

    @Test
    public void testWritesAndEventsReadTheStoreNotTheCache() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            Properties configuration = new Properties();
            configuration.put("config.type", "database");
            configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
            configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            server.start();

            Properties initial = new Properties();
            initial.put("test.changed", "old");
            initial.put("test.removed", "x");
            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(initial));

            // another peer commits behind this node's cache
            commitAsAnotherPeer(server,
                    "UPDATE proxy_config SET pvalue='new' WHERE pname='test.changed'",
                    "DELETE FROM proxy_config WHERE pname='test.removed'",
                    "INSERT INTO proxy_config(pname, pvalue) VALUES ('test.added', 'peer')");

            server.updateMaintenanceMode(true);

            ConfigurationStore store = server.getDynamicConfigurationStore();
            store.reload();
            assertEquals("new", store.getString("test.changed", null));
            assertNull(store.getString("test.removed", null));
            assertEquals("peer", store.getString("test.added", null));
            assertEquals("true", store.getString("carapace.maintenancemode.enabled", null));

            // then an event from that peer
            commitAsAnotherPeer(server, "UPDATE proxy_config SET pvalue='false' WHERE pname='carapace.maintenancemode.enabled'");
            server.reloadConfigurationFromStore();
            assertFalse(server.getCurrentConfiguration().isMaintenanceModeEnabled());
        }
    }

    private static void commitAsAnotherPeer(HttpProxyServer server, String... statements) throws Exception {
        DataSource datasource = Whitebox.getInternalState(server.getDynamicConfigurationStore(), "datasource");
        try (Connection con = datasource.getConnection(); Statement st = con.createStatement()) {
            for (String sql : statements) {
                st.executeUpdate(sql);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    @Ignore
    public void testChangeFiltersConfiguration() throws Exception {

        File databaseFolder = tmpDir.newFolder();
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            Properties configurationAtBoot = new Properties();
            configurationAtBoot.put("db.jdbc.url", "jdbc:herddb:localhost");
            configurationAtBoot.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());

            server.configureAtBoot(new PropertiesConfigurationStore(configurationAtBoot));
            server.start();

            Properties newConfig = new Properties();
            newConfig.put("filter.1.type", "add-x-forwarded-for");
            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(newConfig));

            assertThat(server.getDynamicConfigurationStore(), instanceOf(HerdDBConfigurationStore.class));
            assertEquals(1, server.getFilters().size());
            assertTrue(server.getFilters().get(0) instanceof XForwardedForRequestFilter);

            // add a filter
            Properties configuration = new Properties();
            configuration.put("filter.1.type", "add-x-forwarded-for");
            configuration.put("filter.2.type", "match-user-regexp");
            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(configuration));

            // verify configuration is applied
            assertEquals(2, server.getFilters().size());
            assertTrue(server.getFilters().get(0) instanceof XForwardedForRequestFilter);
            assertTrue(server.getFilters().get(1) instanceof RegexpMapUserIdFilter);
        }

        // reboot, new configuration MUST be kept
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {
            Properties configurationAtBoot = new Properties();
            configurationAtBoot.put("db.jdbc.url", "jdbc:herddb:localhost");
            configurationAtBoot.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configurationAtBoot));

            assertEquals(2, server.getFilters().size());
            assertTrue(server.getFilters().get(0) instanceof XForwardedForRequestFilter);
            assertTrue(server.getFilters().get(1) instanceof RegexpMapUserIdFilter);

            server.start();

            // remove one filter
            Properties configuration = new Properties();
            configuration.put("filter.2.type", "match-user-regexp");
            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(configuration));

        }
        // reboot, new configuration MUST be kept
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {
            Properties configurationAtBoot = new Properties();
            configurationAtBoot.put("db.jdbc.url", "jdbc:herddb:localhost");
            configurationAtBoot.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configurationAtBoot));

            assertEquals(1, server.getFilters().size());
            assertTrue(server.getFilters().get(0) instanceof RegexpMapUserIdFilter);
        }
    }

}
