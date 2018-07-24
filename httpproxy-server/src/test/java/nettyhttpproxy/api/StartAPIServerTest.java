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
import nettyhttpproxy.utils.TestEndpointMapper;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import nettyhttpproxy.utils.RawHttpClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author enrico.olivelli
 */
public class StartAPIServerTest {

    @Test
    public void test() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer("localhost", 0,
                new TestEndpointMapper("localhost", 0));) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");
            server.configure(prop);
            server.start();
            server.startAdminInterface();

            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse resp = client.get("/api/up");
                String s = resp.getBodyString();
                System.out.println("s:" + s);
                assertTrue(s.equals("ok"));
                // API calls cannot be cached by the client (browser)
                assertTrue(resp.getHeaderLines().contains("Cache-Control: no-cache\r\n"));
                // Allow CORS
                assertTrue(resp.getHeaderLines().contains("Access-Control-Allow-Origin: *\r\n"));
            }

        }
    }

    @Test
    public void testCache() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer("localhost", 0,
                new TestEndpointMapper("localhost", 0));) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");
            server.configure(prop);
            server.start();
            server.startAdminInterface();

            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse resp = client.get("/api/cache/info");
                String s = resp.getBodyString();
                System.out.println("s:" + s);
                assertTrue(s.equals("{\"result\":\"ok\",\"hits\":0,\"directMemoryUsed\":0,\"misses\":0,\"heapMemoryUsed\":0,\"cachesize\":0}"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse resp = client.get("/api/cache/flush");
                String s = resp.getBodyString();
                System.out.println("s:" + s);
                assertTrue(s.equals("{\"result\":\"ok\",\"cachesize\":0}"));
            }
        }
    }

    @Test
    public void testBackends() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer("localhost", 0,
                new TestEndpointMapper("localhost", 0));) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");
            server.configure(prop);
            server.start();
            server.startAdminInterface();

            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse resp = client.get("/api/backends");
                String s = resp.getBodyString();
                System.out.println("s:" + s);
                // no backend configured
                assertTrue(s.equals("{}"));
            }

        }
    }

    @Test
    public void testListeners() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer("localhost", 0,
                new TestEndpointMapper("localhost", 0));) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");
            server.configure(prop);

            server.addListener(new NetworkListenerConfiguration("localhost", 1234));
            server.addListener(new NetworkListenerConfiguration("127.0.0.1", 9876));

            server.start();
            server.startAdminInterface();

            // simple request with 2 network listeners
            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse response = client.get("/api/listeners");
                String json = response.getBodyString();

                assertThat(json, containsString("localhost"));
                assertThat(json, containsString("1234"));

                assertThat(json, containsString("127.0.0.1"));
                assertThat(json, containsString("9876"));
            }

        }
    }

    @Test
    public void testCertificates() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer("localhost", 0,
                new TestEndpointMapper("localhost", 0));) {
            Properties prop = new Properties();
            prop.setProperty("http.admin.enabled", "true");
            prop.setProperty("http.admin.port", "8761");
            prop.setProperty("http.admin.host", "localhost");
            server.configure(prop);

            server.addCertificate(new SSLCertificateConfiguration("localhost", "conf/mock1.file", "mock-pass"));
            server.addCertificate(new SSLCertificateConfiguration("127.0.0.1", "conf/mock2.file", "mock-pass"));

            server.start();
            server.startAdminInterface();

            // full list request
            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse response = client.get("/api/certificates");
                String json = response.getBodyString();

                assertThat(json, containsString("localhost"));
                assertThat(json, containsString("conf/mock1.file"));

                assertThat(json, containsString("127.0.0.1"));
                assertThat(json, containsString("conf/mock2.file"));

                assertThat(json, containsString("mock-pass"));
            }

            // single cert request to /{certId}
            try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
                RawHttpClient.HttpResponse response = client.get("/api/certificates/127.0.0.1");
                String json = response.getBodyString();

                assertThat(json, not(containsString("localhost")));
                assertThat(json, not(containsString("conf/mock1.file")));

                assertThat(json, containsString("127.0.0.1"));
                assertThat(json, containsString("conf/mock2.file"));
            }

        }
    }
}
