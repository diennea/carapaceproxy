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
import javax.servlet.http.HttpServletResponse;
import nettyhttpproxy.server.filters.RegexpMapSessionIdFilter;
import nettyhttpproxy.server.filters.RegexpMapUserIdFilter;
import nettyhttpproxy.server.filters.XForwardedForRequestFilter;
import nettyhttpproxy.utils.RawHttpClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author enrico.olivelli
 */
public class StartAPIServerTest extends UseAdminServer {

    @Test
    public void test() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertTrue(s.equals("ok"));
            // API calls cannot be cached by the client (browser)
            assertTrue(resp.getHeaderLines().contains("Cache-Control: no-cache\r\n"));
            // Allow CORS
            assertTrue(resp.getHeaderLines().contains("Access-Control-Allow-Origin: *\r\n"));
        }
    }

    @Test
    public void testUnauthorized() throws Exception {
        // start server with authentication and user test - test
        Properties properties = new Properties();

        properties.put("userrealm.class", "nettyhttpproxy.utils.TestUserRealm");
        properties.put("user.test", "test");

        startAdmin(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", credentials);
            assertThat(resp.getBodyString(), containsString(HttpServletResponse.SC_UNAUTHORIZED + ""));
        }

        // ok credentials
        RawHttpClient.BasicAuthCredentials correctCredentials = new RawHttpClient.BasicAuthCredentials("test", "test");
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", correctCredentials);
            assertTrue(resp.getBodyString().equals("ok"));
        }

        Properties reloadedProperties = new Properties();
        reloadedProperties.put("userrealm.class", "nettyhttpproxy.user.SimpleUserRealm");
        changeDynamicConfiguration(reloadedProperties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", correctCredentials);
            assertTrue(resp.getBodyString().equals("ok"));
        }
    }

    @Test
    public void testCache() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/cache/info", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertThat(s, is("{\"result\":\"ok\",\"hits\":0,\"directMemoryUsed\":0,\"misses\":0,\"heapMemoryUsed\":0,\"totalMemoryUsed\":0,\"cachesize\":0}"));
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/cache/flush", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertThat(s, is("{\"result\":\"ok\",\"cachesize\":0}"));
        }
    }

    @Test
    public void testBackends() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/backends", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            // no backend configured
            assertTrue(s.equals("{}"));
        }
    }

    @Test
    public void testConfig() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String body = "connectionsmanager.maxconnectionsperendpoint=20";
            RawHttpClient.HttpResponse resp = client.executeRequest("POST /api/config/validate HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            // no backend configured
            assertTrue(s.equals("{\"ok\":true,\"error\":null}"));

        }
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String body = "connectionsmanager.maxconnectionsperendpoint=20-BAD-VALUE";
            RawHttpClient.HttpResponse resp = client.executeRequest("POST /api/config/validate HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                    + "\r\n"
                    + body);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            // no backend configured
            assertTrue(s.contains("\"ok\":false"));
            assertTrue(s.contains("Invalid integer value '20-BAD-VALUE' for parameter 'connectionsmanager.maxconnectionsperendpoint'"));
        }
    }

    @Test
    public void testListeners() throws Exception {
        Properties properties = new Properties();

        properties.put("listener.1.host", "localhost");
        properties.put("listener.1.port", "1234");

        properties.put("listener.2.host", "127.0.0.1");
        properties.put("listener.2.port", "9876");

        startAdmin(properties);

        // simple request with 2 network listeners
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/listeners", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("localhost"));
            assertThat(json, containsString("1234"));

            assertThat(json, containsString("127.0.0.1"));
            assertThat(json, containsString("9876"));
        }

    }

    @Test
    public void testCertificates() throws Exception {
        Properties properties = new Properties();

        properties.put("certificate.1.hostname", "localhost");
        properties.put("certificate.1.sslcertfile", "conf/mock1.file");
        properties.put("certificate.1.sslcertfilepassword", "pass");

        properties.put("certificate.2.hostname", "127.0.0.1");
        properties.put("certificate.2.sslcertfile", "conf/mock2.file");
        properties.put("certificate.2.sslcertfilepassword", "pass");

        startAdmin(properties);

        // full list request
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("localhost"));
            assertThat(json, containsString("conf/mock1.file"));

            assertThat(json, containsString("127.0.0.1"));
            assertThat(json, containsString("conf/mock2.file"));
        }

        // single cert request to /{certId}
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/certificates/127.0.0.1", credentials);
            String json = response.getBodyString();

            assertThat(json, not(containsString("localhost")));
            assertThat(json, not(containsString("conf/mock1.file")));

            assertThat(json, containsString("127.0.0.1"));
            assertThat(json, containsString("conf/mock2.file"));
        }
    }

    @Test
    public void testResourcesFilter() throws Exception {
        Properties properties = new Properties();

        properties.put("filter.1.type", "match-user-regexp");
        properties.put("filter.1.param", "param_test_user");
        properties.put("filter.1.regexp", "(.*)");

        properties.put("filter.2.type", "match-session-regexp");
        properties.put("filter.2.param", "param_test_session");
        properties.put("filter.2.regexp", "(.*)");

        properties.put("filter.3.type", "add-x-forwarded-for");
        startAdmin(properties);

        // full list request
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/requestfilters", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString(RegexpMapUserIdFilter.TYPE));
            assertThat(json, containsString("param_test_session"));
            assertThat(json, containsString(RegexpMapSessionIdFilter.TYPE));
            assertThat(json, containsString("param_test_user"));
            assertThat(json, containsString(XForwardedForRequestFilter.TYPE));
        }
    }

}
