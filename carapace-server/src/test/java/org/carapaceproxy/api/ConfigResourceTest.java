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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.carapaceproxy.utils.RawHttpClient;
import org.junit.jupiter.api.Test;

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
        Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", newFolder(tmpDir, "junit").getAbsolutePath());
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
                    + "listener.2.ocsp=true\n"
                    + "listener.2.port=4089\n"
                    + "listener.2.ssl=false";
            resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            assertTrue(resp.isOk());

            // 2) Dumping + check
            resp = client.get("/api/config", credentials);
            dumpedToReApply = resp.getBodyString();
            System.out.println("CONFIG:" + dumpedToReApply);
            assertThat(dumpedToReApply, containsString(body));

            // 3) Update config file + re-Applying
            dumpedToReApply = dumpedToReApply.replace("dynamiccertificatesmanager.period=45", "dynamiccertificatesmanager.period=30");
            dumpedToReApply += "listener.3.enabled=true\n"
                    + "listener.3.host=127.0.0.1\n"
                    + "listener.3.ocsp=true\n"
                    + "listener.3.port=4090\n"
                    + "listener.3.ssl=false";

            resp = client.executeRequest("POST /api/config/apply HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + dumpedToReApply.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + dumpedToReApply);
            assertTrue(resp.isOk());

            // 4) Dumping + check
            resp = client.get("/api/config", credentials);
            System.out.println("CONFIG:" + resp.getBodyString());
            assertThat(resp.getBodyString(), containsString(dumpedToReApply));
        }
        stopServer();
    }

    @Test
    public void testReconfig() throws Exception {
        Properties configuration = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        configuration.put("config.type", "database");
        configuration.put("db.jdbc.url", "jdbc:herddb:localhost");
        configuration.put("db.server.base.dir", newFolder(tmpDir, "junit").getAbsolutePath());
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
            assertTrue(resp.isOk());
        }

        // restart, same "static" configuration
        stopServer();
        buildNewServer();
        startServer(configuration);
        assertEquals(8000, server.getCurrentConfiguration().getConnectTimeout());
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
            assertTrue(resp.isOk());
        }
        assertEquals(9000, server.getCurrentConfiguration().getConnectTimeout());
        assertEquals(30, server.getBackendHealthManager().getPeriod());
        // restart, same "static" confguration
        stopServer();
        buildNewServer();
        startServer(configuration);
        assertEquals(9000, server.getCurrentConfiguration().getConnectTimeout());
        assertEquals(30, server.getBackendHealthManager().getPeriod());
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

}
