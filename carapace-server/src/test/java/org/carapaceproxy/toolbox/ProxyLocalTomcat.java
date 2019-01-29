package org.carapaceproxy.toolbox;

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
import java.net.URL;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.EndpointStats;
import org.carapaceproxy.client.ConnectionsManagerStats;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.server.HttpProxyServer;
import static org.junit.Assert.assertNotNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ProxyLocalTomcat {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", 8086);
        EndpointKey key = new EndpointKey("localhost", 8086);

        ConnectionsManagerStats stats;
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper);) {
            server.start();
            int port = server.getLocalPort();

            String s = IOUtils.toString(new URL("http://localhost:" + port + "/be/admin").toURI(), "utf-8");
            System.out.println("s:" + s);

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            TestUtils.waitForCondition(() -> {
                stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() == 1
                        && epstats.getActiveConnections().intValue() == 0
                        && epstats.getOpenConnections().intValue() == 1;
            }, 100);
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() == 1
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() == 0;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

}
