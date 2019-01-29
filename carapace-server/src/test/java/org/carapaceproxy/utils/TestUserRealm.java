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
package org.carapaceproxy.utils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.user.UserRealm;

/**
 * Test {@link UserRealm} that takes the users from the
 * {@link ConfigurationStore}
 *
 * @author matteo.minardi
 */
public class TestUserRealm implements UserRealm {

    private static final String PREFIX = "user.";

    private final Map<String, String> users = new HashMap<>();

    @Override
    public void configure(ConfigurationStore properties) throws ConfigurationNotValidException {
        properties.forEach(PREFIX, (k, v) -> {
            String username = k.replace(PREFIX, "").trim();
            String password = v.trim();

            users.put(username, password);
        });
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
        String _password = users.get(username);
        if (password.equals(_password)) {
            return username;
        }
        return null;
    }

}
