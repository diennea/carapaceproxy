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
package org.carapaceproxy.user;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import static org.carapaceproxy.utils.StringUtils.trimToNull;

/**
 * User realm that takes the initial configuration of the authorized users from
 * a file specified in the server.properties.
 *
 * @author matteo.minardi
 */
public class FileUserRealm implements UserRealm {

    private static final Logger LOG = Logger.getLogger(FileUserRealm.class.getName());

    public static final String USER_PREFIX = "user.";
    Map<String, String> users = new HashMap<>();

    public static Map<String, String> createUsers(ConfigurationStore configStore) {
        Map<String, String> result = new HashMap<>();
        if (configStore == null) {
            return result;
        }
        configStore.forEach(USER_PREFIX, (username, password) -> {
            username = trimToNull(username);
            password = trimToNull(password);
            if (username == null || password == null || result.containsKey(username)) {
                return;
            }

            LOG.log(Level.INFO, "configured user with username={0}", username);
            result.put(username, password);
        });
        return result;
    }

    @Override
    public void configure(ConfigurationStore configStore) throws ConfigurationNotValidException {
        String path = configStore.getString("userrealm.path", "conf/user.properties");
        Properties properties = new Properties();
        try {
            File userFile = new File(path).getAbsoluteFile();
            if (userFile.isFile()) {
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(userFile), StandardCharsets.UTF_8)) {
                    properties.load(reader);

                    ConfigurationStore usersConfigStore = new PropertiesConfigurationStore(properties);
                    this.users = createUsers(usersConfigStore);
                }
            } else {
                LOG.log(Level.SEVERE, "Path {0} is not a file.", path);
            }
        } catch (IOException ex) {
            throw new ConfigurationNotValidException("File in path " + path + " not valid: " + ex);
        }
    }

    @Override
    public Collection<String> listUsers() {
        return Collections.unmodifiableCollection(users.keySet());
    }

    @Override
    public String login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        if (!users.containsKey(username)) {
            return null;
        }
        String _password = users.get(username);
        if (!password.equals(_password)) {
            return null;
        }
        return username;
    }

}
