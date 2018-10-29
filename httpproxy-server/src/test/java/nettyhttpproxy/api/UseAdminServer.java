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
package nettyhttpproxy.api;

import java.util.Properties;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.HttpProxyServer;
import static nettyhttpproxy.server.HttpProxyServer.buildForTests;
import nettyhttpproxy.server.config.ConfigurationChangeInProgressException;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.utils.RawHttpClient;
import nettyhttpproxy.utils.TestEndpointMapper;
import org.junit.After;
import org.junit.Before;

/**
 *
 * @author matteo.minardi
 */
public class UseAdminServer {

    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASSWORD = "admin";

    public static HttpProxyServer server;
    public static RawHttpClient.BasicAuthCredentials credentials;

    @Before
    public void setupAdmin() throws Exception {
        credentials = new RawHttpClient.BasicAuthCredentials(DEFAULT_USERNAME, DEFAULT_PASSWORD);
        server = buildForTests("localhost", 0, new TestEndpointMapper("localhost", 0));
    }

    @After
    public void stopAdmin() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    public void startAdmin(Properties properties) throws Exception {
        if (properties == null) {
            properties = new Properties();
        }

        Properties prop = new Properties();
        prop.setProperty("http.admin.enabled", "true");
        prop.setProperty("http.admin.port", "8761");
        prop.setProperty("http.admin.host", "localhost");
        prop.putAll(properties);

        if (server != null) {
            server.configure(new PropertiesConfigurationStore(prop));

            server.start();
            server.startAdminInterface();
        }
    }

    public void startAdmin() throws Exception {
        startAdmin(null);
    }

    public void changeDynamicConfiguration(Properties configuration) throws ConfigurationNotValidException, ConfigurationChangeInProgressException, InterruptedException {
        if (server != null) {
            PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
            server.applyDynamicConfiguration(server.buildValidConfiguration(config), config);
        }
    }

}
