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

import java.util.Properties;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests around reconfig while using configuration on database
 *
 * @author enrico.olivelli
 */
public class ReconfigTest extends UseAdminServer {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testConfig() throws Exception {
        Properties configuration = new Properties();

        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        startServer(configuration);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String body = "connectionsmanager.connecttimeout=8000";
            RawHttpClient.HttpResponse resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertTrue(s.equals("{\"ok\":true,\"error\":\"\"}"));

        }

        // restart, same "static" confguration
        stopServer();
        buildNewServer();
        startServer(configuration);
        ConnectionsManagerImpl impl = (ConnectionsManagerImpl) server.getConnectionsManager();
        assertEquals(8000, impl.getConnectTimeout());

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String body = "connectionsmanager.connecttimeout=9000";
            RawHttpClient.HttpResponse resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertTrue(s.equals("{\"ok\":true,\"error\":\"\"}"));

        }
        assertEquals(9000, impl.getConnectTimeout());
        // restart, same "static" confguration
        stopServer();
        buildNewServer();
        startServer(configuration);
        impl = (ConnectionsManagerImpl) server.getConnectionsManager();
        assertEquals(9000, impl.getConnectTimeout());
    }

}
