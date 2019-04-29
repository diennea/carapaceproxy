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

import java.util.Base64;
import java.util.Properties;
import javax.servlet.http.HttpServletResponse;
import static org.carapaceproxy.api.CertificatesResource.stateToStatusString;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.certiticates.DynamicCertificate;
import org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState;
import org.carapaceproxy.server.certiticates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.MatchAllRequestMatcher;
import org.carapaceproxy.server.filters.RegexpMapSessionIdFilter;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.utils.RawHttpClient;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
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

        properties.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm");
        properties.put("user.test", "test");

        startServer(properties);

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
        reloadedProperties.put("userrealm.class", "org.carapaceproxy.user.SimpleUserRealm");
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
            // no backend configured
            assertTrue(s.equals("{}"));
        }
    }

    @Test
    public void testRoutes() throws Exception {
        Properties properties = new Properties();
        properties.put("route.0.id", "id0");
        properties.put("route.0.action", "id-action");
        properties.put("route.0.enabled", "true");
        properties.put("route.0.match", "all");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/routes", credentials);
            String s = resp.getBodyString();
            assertTrue(s.contains("id0"));
            assertTrue(s.contains("id-action"));
            assertTrue(s.contains("true"));
            assertTrue(s.contains(new MatchAllRequestMatcher().getDescription()));
        }
    }

    @Test
    public void testActions() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/actions", credentials);
            String s = resp.getBodyString();
            // Default actions
            assertTrue(s.contains("\"not-found\",\"type\":\"static\""));
            assertTrue(s.contains("\"cache-if-possible\",\"type\":\"cache\""));
            assertTrue(s.contains("\"internal-error\",\"type\":\"static\""));
            assertTrue(s.contains("\"acme-challenge\",\"type\":\"acme-challenge\""));
            assertTrue(s.contains("\"proxy-all\",\"type\":\"proxy\""));
        }
    }

    @Test
    public void testDirectors() throws Exception {
        Properties properties = new Properties();
        properties.put("director.1.backends", "*");
        properties.put("director.1.enabled", "false");
        properties.put("director.1.id", "*");

        properties.put("backend.0.id", "localhost:8086");
        properties.put("backend.0.host", "localhost");
        properties.put("backend.0.port", "8086");
        properties.put("backend.0.enabled", "true");
        properties.put("backend.1.id", "localhost:8087");
        properties.put("backend.1.host", "localhost");
        properties.put("backend.1.port", "8087");
        properties.put("backend.1.enabled", "true");

        properties.put("director.2.backends", "localhost:8086,localhost:8087");
        properties.put("director.2.enabled", "true");
        properties.put("director.2.id", "iddirector2");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/directors", credentials);
            String s = resp.getBodyString();
            assertTrue(s.contains("\"id\":\"iddirector2\",\"backends\":[\"localhost:8086\",\"localhost:8087\"]"));
        }
    }

    @Test
    public void testConfig() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String body = "#first line is a comment\n"
                    + "connectionsmanager.maxconnectionsperendpoint=20";
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
            assertTrue(s.equals("{\"ok\":true,\"error\":\"\"}"));

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

        startServer(properties);

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
        final String dynDomain = "prova.diennea.com";
        Properties properties = new Properties();

        properties.put("certificate.1.hostname", "localhost");
        properties.put("certificate.1.sslcertfile", "conf/mock1.file");
        properties.put("certificate.1.sslcertfilepassword", "pass");

        properties.put("certificate.2.hostname", "127.0.0.1");
        properties.put("certificate.2.sslcertfile", "conf/mock2.file");
        properties.put("certificate.2.sslcertfilepassword", "pass");

        // Dynamic certificate
        properties.put("certificate.3.hostname", dynDomain);
        properties.put("certificate.3.dynamic", "true");

        startServer(properties);

        // Static certificates
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("localhost"));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":null"));
            assertThat(json, containsString("conf/mock1.file"));

            assertThat(json, containsString("127.0.0.1"));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":null"));
            assertThat(json, containsString("conf/mock2.file"));

            // single cert request to /{certId}
            response = client.get("/api/certificates/127.0.0.1", credentials);
            json = response.getBodyString();
            assertThat(json, not(containsString("localhost")));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":null"));
            assertThat(json, not(containsString("conf/mock1.file")));
        }

        // Dynamic certificate
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString(dynDomain));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"waiting\""));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + dynDomain, credentials);
            json = response.getBodyString();
            assertThat(json, containsString(dynDomain));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"waiting\""));

            // Changing dynamic certificate state
            DynamicCertificatesManager man = server.getDynamicCertificateManager();
            for (DynamicCertificateState state: DynamicCertificate.DynamicCertificateState.values()) {
                man.setStateOfCertificate(dynDomain, state);
                response = client.get("/api/certificates", credentials);
                json = response.getBodyString();
                assertThat(json, containsString(dynDomain));
                assertThat(json, containsString("\"dynamic\":true"));
                assertThat(json, containsString("\"status\":\"" + stateToStatusString(state) + "\""));

                response = client.get("/api/certificates/" + dynDomain, credentials);
                json = response.getBodyString();
                assertThat(json, containsString(dynDomain));
                assertThat(json, containsString("\"dynamic\":true"));
                assertThat(json, containsString("\"status\":\"" + stateToStatusString(state) + "\""));
            }

            // Downloading
            ConfigurationStore store = new PropertiesConfigurationStore(properties);
            String base64Chain = Base64.getEncoder().encodeToString("CHAIN".getBytes());
            CertificateData certData = new CertificateData(dynDomain, "", base64Chain, true);
            store.saveCertificate(certData);
            man.setConfigurationStore(store);
            man.setStateOfCertificate(dynDomain, DynamicCertificate.DynamicCertificateState.AVAILABLE);
            response = client.get("/api/certificates/" + dynDomain + "/download", credentials);
            assertEquals("CHAIN", response.getBodyString());
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
        startServer(properties);

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

    @Test
    public void testUserRealm() throws Exception {
        Properties properties = new Properties();

        properties.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm");

        properties.put("user.test", "test");
        properties.put("user.test1", "test1");
        properties.put("user.test2", "test2");

        startServer(properties);

        RawHttpClient.BasicAuthCredentials c = new RawHttpClient.BasicAuthCredentials("test", "test");

        // full list request
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse response = client.get("/api/users/all", c);
            String json = response.getBodyString();

            assertThat(json, containsString("test1"));
            assertThat(json, containsString("test2"));
        }
    }

}
