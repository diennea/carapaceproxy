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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.carapaceproxy.configstore.HerdDBConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 * @author enrico.olivelli
 */
public class DatabaseConfigurationIT {

    @TempDir
    public File tmpDir;

    @Test
    void bootWithDatabaseStore() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {

            Properties configuration = new Properties();

            configuration.put("config.type", "database");
            configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
            configuration.put("db.server.base.dir", newFolder(tmpDir, "junit").getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));

            server.start();
            assertThat(server.getDynamicConfigurationStore()).isInstanceOf(HerdDBConfigurationStore.class);

        }

    }

    /**
     * Dynamic filter configuration must survive a reboot of a HerdDB-backed server: filters added
     * through the dynamic-config API are persisted to the database and rebuilt at the next boot.
     * Restored from testChangeFiltersConfiguration, disabled since it was written: it never ran,
     * because it missed {@code config.type=database} and used a fresh db dir per reboot.
     */
    @SuppressWarnings("deprecation")
    @Test
    void filtersConfigurationSurvivesReboot() throws Exception {
        // A single, shared HerdDB data dir reused across every reboot, so configuration persists.
        final String dbDir = newFolder(tmpDir, "herddb").getAbsolutePath();

        // Boot #1: add filters through the HerdDB-backed dynamic store.
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(dbBootConfig(dbDir)));
            server.start();
            assertThat(server.getDynamicConfigurationStore()).isInstanceOf(HerdDBConfigurationStore.class);

            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(
                    filters("filter.1.type", XForwardedForRequestFilter.TYPE)));
            assertThat(server.getFilters()).hasSize(1);
            assertThat(server.getFilters().get(0)).isInstanceOf(XForwardedForRequestFilter.class);

            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(filters(
                    "filter.1.type", XForwardedForRequestFilter.TYPE,
                    "filter.2.type", RegexpMapUserIdFilter.TYPE)));
            assertThat(server.getFilters()).hasSize(2);
            assertThat(server.getFilters().get(0)).isInstanceOf(XForwardedForRequestFilter.class);
            assertThat(server.getFilters().get(1)).isInstanceOf(RegexpMapUserIdFilter.class);
        }

        // Boot #2: the two filters must have persisted; then reduce to a single filter.
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(dbBootConfig(dbDir)));
            assertThat(server.getFilters()).hasSize(2);
            assertThat(server.getFilters().get(0)).isInstanceOf(XForwardedForRequestFilter.class);
            assertThat(server.getFilters().get(1)).isInstanceOf(RegexpMapUserIdFilter.class);

            server.start();

            server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(
                    filters("filter.1.type", RegexpMapUserIdFilter.TYPE)));
            assertThat(server.getFilters()).hasSize(1);
            assertThat(server.getFilters().get(0)).isInstanceOf(RegexpMapUserIdFilter.class);
        }

        // Boot #3: the single remaining filter must have persisted.
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {
            server.configureAtBoot(new PropertiesConfigurationStore(dbBootConfig(dbDir)));
            assertThat(server.getFilters()).hasSize(1);
            assertThat(server.getFilters().get(0)).isInstanceOf(RegexpMapUserIdFilter.class);
        }
    }

    private static Properties dbBootConfig(String dbDir) {
        Properties c = new Properties();
        c.put("config.type", "database");
        c.put("db.jdbc.url", "jdbc:herddb:localhost");
        c.put("db.server.base.dir", dbDir);
        return c;
    }

    private static Properties filters(String... keyValues) {
        Properties c = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            c.put(keyValues[i], keyValues[i + 1]);
        }
        return c;
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
