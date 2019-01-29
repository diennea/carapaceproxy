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
package org.carapaceproxy.users;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.HttpProxyServer;
import static org.carapaceproxy.server.HttpProxyServer.buildForTests;
import org.carapaceproxy.user.FileUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.carapaceproxy.utils.TestEndpointMapper;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author matteo.minardi
 */
public class FileUserRealmTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private File createUserFile(Map<String, String> users) throws Exception {
        Properties properties = new Properties();
        users.forEach((username, password) -> properties.put(FileUserRealm.USER_PREFIX + username, password));

        File outFile = tmpDir.newFile("user" + System.currentTimeMillis() + " .properties");
        try (OutputStream out = new FileOutputStream(outFile);) {
            properties.store(out, "test_users_file");
        }

        return outFile;
    }

    @Test
    public void testFileUserRealm() throws Exception {
        try (HttpProxyServer server = buildForTests("localhost", 0, new TestEndpointMapper("localhost", 0))) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");

            Map<String, String> users = new HashMap<>();
            users.put("test1", "pass1");
            users.put("test2", "pass2");
            users.put("test3", "pass3");

            File usersFile = createUserFile(users);
            prop.setProperty("userrealm.class", "org.carapaceproxy.user.FileUserRealm");
            prop.setProperty("userrealm.path", usersFile.getPath());

            ConfigurationStore configStore = new PropertiesConfigurationStore(prop);

            server.configureAtBoot(configStore);
            server.start();
            server.startAdminInterface();

            UserRealm userRealm = server.getRealm();
            Collection<String> resultUsers = userRealm.listUsers();

            assertThat(resultUsers.size(), is(users.size()));
            for (String username : users.keySet()) {
                String login = userRealm.login(username, users.get(username));

                assertNotNull(login); // login success
                assertThat(username, is(login));
            }

            String wrongLogin = userRealm.login("wrong", "login");
            assertNull(wrongLogin);
        }
    }

    @Test
    public void testFileUserRealmRefresh() throws Exception {
        try (HttpProxyServer server = buildForTests("localhost", 0, new TestEndpointMapper("localhost", 0))) {
            Map<String, String> users = new HashMap<>();
            users.put("test1", "pass1");

            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");

            File usersFile = createUserFile(users);
            prop.setProperty("userrealm.class", "org.carapaceproxy.user.FileUserRealm");
            prop.setProperty("userrealm.path", usersFile.getPath());

            ConfigurationStore configStore = new PropertiesConfigurationStore(prop);
            server.configureAtBoot(configStore);

            server.start();
            server.startAdminInterface();

            UserRealm userRealm = server.getRealm();
            Collection<String> resultUsers = userRealm.listUsers();
            assertThat(resultUsers.size(), is(users.size()));

            users.put("test2", "pass2");
            users.put("test3", "pass3");
            users.put("test4", "pass4");

            Properties newProperties = new Properties();

            File newUserFile = createUserFile(users);
            newProperties.setProperty("userrealm.class", "org.carapaceproxy.user.FileUserRealm");
            newProperties.setProperty("userrealm.path", newUserFile.getPath());

            ConfigurationStore newConfigStore = new PropertiesConfigurationStore(newProperties);
            server.applyDynamicConfiguration(newConfigStore);

            userRealm = server.getRealm();
            resultUsers = userRealm.listUsers();
            assertThat(resultUsers.size(), is(users.size()));
        }
    }

    @Test
    public void testFileRelativePath() throws Exception {
        try (HttpProxyServer server = buildForTests("localhost", 0, new TestEndpointMapper("localhost", 0))) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");

            // create new file in the server and access it with relative path
            File userPropertiesFile = new File("target/testuser" + System.currentTimeMillis() + ".properties");
            userPropertiesFile.createNewFile();

            Properties userProperties = new Properties();
            userProperties.put("user.test1", "pass1");
            userProperties.put("user.test2", "pass2");
            // store them in the file
            try (OutputStream out = new FileOutputStream(userPropertiesFile)) {
                userProperties.store(out, "test_users_file");
            }
            // relative path
            prop.setProperty("userrealm.class", "org.carapaceproxy.user.FileUserRealm");
            prop.setProperty("userrealm.path", "target/" + userPropertiesFile.getName());

            ConfigurationStore configStore = new PropertiesConfigurationStore(prop);
            server.configureAtBoot(configStore);
            server.start();
            server.startAdminInterface();

            UserRealm userRealm = server.getRealm();
            assertNotNull(userRealm.login("test1", "pass1"));
            assertNotNull(userRealm.login("test2", "pass2"));

            assertNull(userRealm.login("test1", "wrongpass"));

            userPropertiesFile.delete();
        }
    }

}
