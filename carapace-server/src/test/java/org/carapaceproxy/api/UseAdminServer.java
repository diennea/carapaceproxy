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
package org.carapaceproxy.api;

import static org.carapaceproxy.core.HttpProxyServer.buildForTests;
import static org.junit.Assert.assertNull;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author matteo.minardi
 */
public class UseAdminServer {

    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASSWORD = "admin";
    public static final int DEFAULT_ADMIN_PORT = 8761;

    protected static final Properties HTTP_ADMIN_SERVER_CONFIG = new Properties();
    static {
        HTTP_ADMIN_SERVER_CONFIG.setProperty("http.admin.enabled", "true");
        HTTP_ADMIN_SERVER_CONFIG.setProperty("http.admin.port", DEFAULT_ADMIN_PORT + "");
        HTTP_ADMIN_SERVER_CONFIG.setProperty("http.admin.host", "localhost");
    }

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    public HttpProxyServer server;
    public RawHttpClient.BasicAuthCredentials credentials;

    @Before
    public void buildNewServer() throws Exception {
        assertNull(server);
        credentials = new RawHttpClient.BasicAuthCredentials(DEFAULT_USERNAME, DEFAULT_PASSWORD);
        File serverRoot = tmpDir.getRoot(); // at every reboot we must keep the same directory
        server = buildForTests("localhost", 0, new TestEndpointMapper("localhost", 0), serverRoot);
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    public void startServer(Properties properties) throws Exception {
        if (properties == null) {
            properties = new Properties();
        }

        fixAccessLogFileConfiguration(properties);

        if (server != null) {
            server.configureAtBoot(new PropertiesConfigurationStore(properties));

            server.start();
            server.startAdminInterface();
            server.getCache().getStats().resetCacheMetrics();
        }
    }

    public void startAdmin() throws Exception {
        startServer(new Properties(HTTP_ADMIN_SERVER_CONFIG));
    }

    public void changeDynamicConfiguration(Properties configuration) throws ConfigurationChangeInProgressException, InterruptedException, IOException {
        if (server != null) {
            fixAccessLogFileConfiguration(configuration);
            PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
            server.applyDynamicConfigurationFromAPI(config);
        }
    }

    private void fixAccessLogFileConfiguration(Properties properties) throws IOException {
        if (!properties.containsKey("admin.accesslog.path")) {
            properties.put("admin.accesslog.path", tmpDir.newFile().getAbsolutePath());
        }
    }

}
