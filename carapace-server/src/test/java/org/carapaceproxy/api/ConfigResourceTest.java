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
import org.carapaceproxy.utils.RawHttpClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests around {@link ConfigResource} while using configuration on database
 *
 * @author enrico.olivelli
 */
public class ConfigResourceTest extends UseAdminServer {

    @Test
    // 0) Dumping of start-up configuration
    // 1) applying of dynamic configuration
    // 2) dumping of the current configuration
    // 3) updating+applying dumped configuration
    // 4) dumping updated configuration
    public void testDynamicConfigurationDumpingAndApplying() throws Exception {
        Properties configuration = new Properties();
        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        configuration.put("dynamiccertificatesmanager.period", 25); // will be ignore due to db-mode
        startServer(configuration);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            // 0) Dumping + check
            String dumpedToReApply;
            RawHttpClient.HttpResponse resp = client.get("/api/config", credentials);
            dumpedToReApply = resp.getBodyString();
            System.out.println("CONFIG:" + dumpedToReApply);
            assertFalse(dumpedToReApply.contains("dynamiccertificatesmanager"));

            // 1) Applying
            String body = "dynamiccertificatesmanager.period=45\n"
                    + "listener.2.defaultcertificate=*\n"
                    + "listener.2.enabled=true\n"
                    + "listener.2.host=0.0.0.0\n"
                    + "listener.2.ocps=true\n"
                    + "listener.2.port=4089\n"
                    + "listener.2.ssl=false";
            resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertTrue(s.equals("{\"ok\":true,\"error\":\"\"}"));

            // 2) Dumping + check
            resp = client.get("/api/config", credentials);
            dumpedToReApply = resp.getBodyString();
            System.out.println("CONFIG:" + dumpedToReApply);
            assertThat(dumpedToReApply, containsString(body));

            // 3) Update config file + re-Applying
            dumpedToReApply = dumpedToReApply.replace("dynamiccertificatesmanager.period=45", "dynamiccertificatesmanager.period=30");
            dumpedToReApply += "listener.3.enabled=true\n"
                    + "listener.3.host=0.0.0.1\n"
                    + "listener.3.ocps=true\n"
                    + "listener.3.port=4090\n"
                    + "listener.3.ssl=false";

            resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + dumpedToReApply.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + dumpedToReApply);
            s = resp.getBodyString();
            System.out.println("s:" + s);
            assertTrue(s.equals("{\"ok\":true,\"error\":\"\"}"));

            // 4) Dumping + check
            resp = client.get("/api/config", credentials);
            System.out.println("CONFIG:" + resp.getBodyString());
            assertThat(resp.getBodyString(), containsString(dumpedToReApply));
        }
        stopServer();
    }

    @Test
    public void testReconfig() throws Exception {
        Properties configuration = new Properties();

        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
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
