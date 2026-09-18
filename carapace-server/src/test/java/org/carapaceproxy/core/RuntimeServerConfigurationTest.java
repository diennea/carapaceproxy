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
package org.carapaceproxy.core;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.junit.Test;

/**
 * Tests for the validation {@link RuntimeServerConfiguration#configure} performs on the properties it reads.
 */
public class RuntimeServerConfigurationTest {

    private static RuntimeServerConfiguration configure(String... keyValues) throws ConfigurationNotValidException {
        final var props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        final var config = new RuntimeServerConfiguration();
        config.configure(new PropertiesConfigurationStore(props));
        return config;
    }

    @Test
    public void testNegativeAcmeRateLimit() {
        final var e = assertThrows(ConfigurationNotValidException.class,
                () -> configure("dynamiccertificatesmanager.ratelimit", "-1"));
        assertThat(e.getMessage(), containsString("dynamiccertificatesmanager.ratelimit"));
    }
}
