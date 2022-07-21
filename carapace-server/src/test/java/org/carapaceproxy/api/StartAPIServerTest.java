/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.api;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.filters.*;
import org.carapaceproxy.server.mapper.requestmatcher.MatchAllRequestMatcher;

import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestUtils;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import org.junit.Assert;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import static org.carapaceproxy.utils.APIUtils.certificateStateToString;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesUtils.KEYSTORE_PW;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.hamcrest.MatcherAssert.assertThat;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 *
 * @author enrico.olivelli
 */
public class StartAPIServerTest extends UseAdminServer {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

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
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        properties.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm"); // configured at boot only.
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

        // Add new user
        Properties reloadedProperties = new Properties();
        reloadedProperties.put("user.test2", "test2");
        changeDynamicConfiguration(reloadedProperties);
        correctCredentials = new RawHttpClient.BasicAuthCredentials("test2", "test2");
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
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
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
            assertTrue(s.contains("\"proxy-all\",\"type\":\"proxy\""));
        }
    }

    @Test
    public void testDirectors() throws Exception {
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
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
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

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
        final String dynDomain = "dynamic.test.tld";
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        X509Certificate certificate = (X509Certificate) originalChain[0];
        String serialNumber1 = certificate.getSerialNumber().toString(16).toUpperCase();
        String expiringDate1 = certificate.getNotAfter().toString();
        byte[] keystoreData = createKeystore(originalChain, endUserKeyPair.getPrivate());
        File mock1 = tmpFolder.newFile("mock1.p12");
        Files.write(mock1.toPath(), keystoreData);
        properties.put("certificate.1.hostname", "localhost");
        properties.put("certificate.1.file", mock1.getAbsolutePath());
        properties.put("certificate.1.password", KEYSTORE_PW);

        endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        originalChain = generateSampleChain(endUserKeyPair, true);
        certificate = (X509Certificate) originalChain[0];
        String serialNumber2 = certificate.getSerialNumber().toString(16).toUpperCase();
        String expiringDate2 = certificate.getNotAfter().toString();
        keystoreData = createKeystore(originalChain, endUserKeyPair.getPrivate());
        File mock2 = tmpFolder.newFile("mock2.p12");
        Files.write(mock2.toPath(), keystoreData);
        properties.put("certificate.2.hostname", "127.0.0.1");
        properties.put("certificate.2.file", mock2.getAbsolutePath());
        properties.put("certificate.2.password", KEYSTORE_PW);

        // Acme certificate
        properties.put("certificate.3.hostname", dynDomain);
        properties.put("certificate.3.mode", "acme");

        startServer(properties);

        DynamicCertificatesManager man = server.getDynamicCertificatesManager();

        // need to explicitly add 'cause DynamicCertificatesManager never run
        ConfigurationStore store = server.getDynamicConfigurationStore();
        endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        originalChain = generateSampleChain(endUserKeyPair, false);
        certificate = (X509Certificate) originalChain[0];
        String serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
        String expiringDate = certificate.getNotAfter().toString();
        String dynChain = Base64.getEncoder().encodeToString(createKeystore(originalChain, endUserKeyPair.getPrivate()));
        store.saveCertificate(new CertificateData(dynDomain, "", dynChain, WAITING, "", ""));
        man.setStateOfCertificate(dynDomain, WAITING); // this reloads certificates from the store

        // Static certificates
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("localhost"));
            assertThat(json, containsString("\"mode\":\"static\""));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":\"available\""));
            assertThat(json, containsString("\"sslCertificateFile\":\"" + mock1.getAbsolutePath() + "\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber1 + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate1 + "\""));

            assertThat(json, containsString("127.0.0.1"));
            assertThat(json, containsString("\"mode\":\"static\""));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":\"expired\""));
            assertThat(json, containsString("\"sslCertificateFile\":\"" + mock2.getAbsolutePath() + "\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber2 + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate2 + "\""));

            // single cert request to /{certId}
            response = client.get("/api/certificates/127.0.0.1", credentials);
            json = response.getBodyString();
            assertThat(json, not(containsString("localhost")));
            assertThat(json, containsString("\"mode\":\"static\""));
            assertThat(json, containsString("\"dynamic\":false"));
            assertThat(json, containsString("\"status\":\"expired\""));
            assertThat(json, containsString("\"sslCertificateFile\":\"" + mock2.getAbsolutePath() + "\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber2 + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate2 + "\""));
        }

        // Acme certificate
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString(dynDomain));
            assertThat(json, containsString("\"mode\":\"acme\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"waiting\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + dynDomain, credentials);
            json = response.getBodyString();
            assertThat(json, containsString(dynDomain));
            assertThat(json, containsString("\"mode\":\"acme\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"waiting\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // Changing dynamic certificate state
            for (DynamicCertificateState state : DynamicCertificateState.values()) {
                man.setStateOfCertificate(dynDomain, state);
                response = client.get("/api/certificates", credentials);
                json = response.getBodyString();
                assertThat(json, containsString(dynDomain));
                assertThat(json, containsString("\"mode\":\"acme\""));
                assertThat(json, containsString("\"dynamic\":true"));
                assertThat(json, containsString("\"status\":\"" + certificateStateToString(state) + "\""));

                response = client.get("/api/certificates/" + dynDomain, credentials);
                json = response.getBodyString();
                assertThat(json, containsString(dynDomain));
                assertThat(json, containsString("\"mode\":\"acme\""));
                assertThat(json, containsString("\"dynamic\":true"));
                assertThat(json, containsString("\"status\":\"" + certificateStateToString(state) + "\""));
            }

            // Downloading
            CertificateData cert = store.loadCertificateForDomain(dynDomain);
            byte[] newKeystore = createKeystore(generateSampleChain(endUserKeyPair, false), KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE).getPrivate());
            cert.setChain(Base64.getEncoder().encodeToString(newKeystore));
            store.saveCertificate(cert);
            man.setStateOfCertificate(dynDomain, DynamicCertificateState.AVAILABLE);
            response = client.get("/api/certificates/" + dynDomain + "/download", credentials);
            assertTrue(Arrays.equals(newKeystore, response.getBody()));
        }

        // Manual certificate
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String manualDomain = "manual.test.tld";

            int certsCount = server.getCurrentConfiguration().getCertificates().size();

            // Uploading trash-stuff
            RawHttpClient.HttpResponse resp = uploadCertificate(manualDomain, null, "fake-chain".getBytes(), client, credentials);
            String s = resp.getBodyString();
            assertTrue(s.contains("ERROR"));

            // Uploading real certificate
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            originalChain = generateSampleChain(endUserKeyPair, false);
            certificate = (X509Certificate) originalChain[0];
            serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
            expiringDate = certificate.getNotAfter().toString();
            byte[] chain1 = createKeystore(originalChain, endUserKeyPair.getPrivate());
            resp = uploadCertificate(manualDomain, null, chain1, client, credentials);
            s = resp.getBodyString();
            assertTrue(s.contains("SUCCESS"));

            int certsCount2 = server.getCurrentConfiguration().getCertificates().size();
            assertEquals(certsCount + 1, certsCount2);

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString(manualDomain));
            assertThat(json, containsString("\"mode\":\"manual\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"available\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + manualDomain, credentials);
            json = response.getBodyString();
            assertThat(json, containsString(manualDomain));
            assertThat(json, containsString("\"mode\":\"manual\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"available\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // Downloading
            response = client.get("/api/certificates/" + manualDomain + "/download", credentials);
            assertTrue(Arrays.equals(chain1, response.getBody()));

            // Certificate updating
            // Uploading
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            originalChain = generateSampleChain(endUserKeyPair, true);
            certificate = (X509Certificate) originalChain[0];
            serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
            expiringDate = certificate.getNotAfter().toString();
            byte[] chain2 = createKeystore(originalChain, endUserKeyPair.getPrivate());
            assertFalse(Arrays.equals(chain1, chain2));
            resp = uploadCertificate(manualDomain, null, chain2, client, credentials);
            s = resp.getBodyString();
            assertTrue(s.contains("SUCCESS"));

            //  check properties (certificate) not duplicated
            int certsCount3 = server.getCurrentConfiguration().getCertificates().size();
            assertEquals(certsCount2, certsCount3);

            // full list request
            response = client.get("/api/certificates", credentials);
            json = response.getBodyString();

            assertThat(json, containsString(manualDomain));
            assertThat(json, containsString("\"mode\":\"manual\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"expired\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + manualDomain, credentials);
            json = response.getBodyString();
            assertThat(json, containsString(manualDomain));
            assertThat(json, containsString("\"mode\":\"manual\""));
            assertThat(json, containsString("\"dynamic\":true"));
            assertThat(json, containsString("\"status\":\"expired\""));
            assertThat(json, containsString("\"serialNumber\":\"" + serialNumber + "\""));
            assertThat(json, containsString("\"expiringDate\":\"" + expiringDate + "\""));

            // Downloading
            response = client.get("/api/certificates/" + manualDomain + "/download", credentials);
            assertTrue(Arrays.equals(chain2, response.getBody()));
        }
    }

    @Test
    public void testResourcesFilter() throws Exception {
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        properties.put("filter.1.type", "match-user-regexp");
        properties.put("filter.1.param", "param_test_user");
        properties.put("filter.1.regexp", "(.*)");

        properties.put("filter.2.type", "match-session-regexp");
        properties.put("filter.2.param", "param_test_session");
        properties.put("filter.2.regexp", "(.*)");

        properties.put("filter.3.type", "add-x-forwarded-for");

        properties.put("filter.4.type", "add-x-tls-protocol");
        properties.put("filter.5.type", "add-x-tls-cipher");
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
            assertThat(json, containsString(XTlsProtocolRequestFilter.TYPE));
            assertThat(json, containsString(XTlsCipherRequestFilter.TYPE));
        }
    }

    @Test
    public void testUserRealm() throws Exception {
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

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

    @Test
    public void testHttpsApi() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        Properties properties = new Properties();
        properties.setProperty("http.admin.enabled", "true");
        properties.setProperty("http.admin.host", "localhost");
        properties.setProperty("https.admin.port", "8762");
        properties.setProperty("https.admin.sslcertfile", certificate);
        properties.setProperty("https.admin.sslcertfilepassword", "testproxy");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8762, true)) {
            RawHttpClient.HttpResponse response = client.get("/api/config", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("https.admin.sslcertfile=" + certificate));
        }

        IOException exc = null;
        try (RawHttpClient client = new RawHttpClient("localhost", 8762, false)) {
            client.get("/api/config", credentials);
        } catch (IOException ex) {
            exc = ex;
        }

        Assert.assertNotNull(exc);
        Assert.assertThat(exc.getMessage(), containsString("bad response, does not start with HTTP/1.1"));
    }

    @Test
    public void testHttpAndHttpsApi() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        properties.setProperty("https.admin.port", "8762");
        properties.setProperty("https.admin.sslcertfile", certificate);
        properties.setProperty("https.admin.sslcertfilepassword", "testproxy");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8762, true)) {
            RawHttpClient.HttpResponse response = client.get("/api/config", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("https.admin.sslcertfile=" + certificate));
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761, false)) {
            RawHttpClient.HttpResponse response = client.get("/api/config", credentials);
            String json = response.getBodyString();

            assertThat(json, containsString("https.admin.sslcertfile=" + certificate));
        }
    }

    @Test
    public void testApiRequestsLogger() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());

        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        properties.setProperty("https.admin.port", "8762");
        properties.setProperty("https.admin.sslcertfile", certificate);
        properties.setProperty("https.admin.sslcertfilepassword", "testproxy");

        File accessLog = tmpDir.newFile().getAbsoluteFile();
        properties.put("admin.accesslog.path", accessLog.getAbsolutePath());

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8762, true)) {
            client.get("/api/config", credentials);
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761, false)) {
            client.get("/api/config", credentials);
        }

        stopServer();

        try (BufferedReader reader = new BufferedReader(new FileReader(accessLog))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                assertThat(line, containsString("\"GET /api/config HTTP/1.1\" 200"));
                lineCount++;
            }

            assertEquals(2, lineCount);
        }
    }

}
