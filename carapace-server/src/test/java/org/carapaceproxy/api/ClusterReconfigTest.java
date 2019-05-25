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
import org.apache.curator.test.TestingServer;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.utils.RawHttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ClusterReconfigTest extends UseAdminServer {

    @Test
    public void testReconfigInClusterMode() throws Exception {
        try (TestingServer testingServer = new TestingServer(2229, tmpDir.newFolder());) {
            testingServer.start();
            Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);

            configuration.put("config.type", "database");
            configuration.put("mode", "cluster");
            configuration.put("zkAddress", testingServer.getConnectString());
            configuration.put("db.bookie.allowLoopback", "true");

            startServer(configuration);

            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                String body = "connectionsmanager.connecttimeout=8000\n"
                        + "healthmanager.period=25";
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

            // restart, same "static" configuration
            stopServer();
            buildNewServer();
            startServer(configuration);
            ConnectionsManagerImpl impl = (ConnectionsManagerImpl) server.getConnectionsManager();
            assertEquals(8000, impl.getConnectTimeout());
            assertEquals(25, server.getBackendHealthManager().getPeriod());
            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                String body = "connectionsmanager.connecttimeout=9000\n"
                        + "healthmanager.period=30";
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
            assertEquals(30, server.getBackendHealthManager().getPeriod());
            // restart, same "static" confguration
            stopServer();
            buildNewServer();
            startServer(configuration);
            impl = (ConnectionsManagerImpl) server.getConnectionsManager();
            assertEquals(9000, impl.getConnectTimeout());
            assertEquals(30, server.getBackendHealthManager().getPeriod());

        }
    }
}
