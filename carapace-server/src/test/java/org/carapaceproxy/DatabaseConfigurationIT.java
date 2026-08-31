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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.carapaceproxy.configstore.HerdDBConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
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
    public void testBootWithDatabaseStore() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, newFolder(tmpDir, "junit"))) {

            Properties configuration = new Properties();

            configuration.put("config.type", "database");
            configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
            configuration.put("db.server.base.dir", newFolder(tmpDir, "junit").getAbsolutePath());
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));

            server.start();
            assertThat(server.getDynamicConfigurationStore(), instanceOf(HerdDBConfigurationStore.class));

        }

    }

    @SuppressWarnings("deprecation")
    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs) + "-" + System.nanoTime();
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

}
