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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.APIUtils.certificateStateToString;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.carapaceproxy.utils.CertificatesUtils.KEYSTORE_PW;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.servlet.http.HttpServletResponse;
import net.javacrumbs.jsonunit.core.Option;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.filters.RegexpMapSessionIdFilter;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.filters.XTlsCipherRequestFilter;
import org.carapaceproxy.server.filters.XTlsProtocolRequestFilter;
import org.carapaceproxy.server.mapper.requestmatcher.MatchAllRequestMatcher;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 *
 * @author enrico.olivelli
 */
public class StartAPIServerIT extends UseAdminServer {

    @TempDir
    public File tmpFolder;

    @Test
    void test() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertThat(s).isEqualTo("ok");
            // API calls cannot be cached by the client (browser)
            assertThat(resp.getHeaderLines()).contains("Cache-Control: no-cache\r\n");
            // Allow CORS
            assertThat(resp.getHeaderLines()).contains("Access-Control-Allow-Origin: *\r\n");
        }
    }

    @Test
    void unauthorized() throws Exception {
        // start server with authentication and user test - test
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        properties.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm"); // configured at boot only.
        properties.put("user.test", "test");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", credentials);
            assertThat(resp.getBodyString()).contains(HttpServletResponse.SC_UNAUTHORIZED + "");
        }

        // ok credentials
        RawHttpClient.BasicAuthCredentials correctCredentials = new RawHttpClient.BasicAuthCredentials("test", "test");
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", correctCredentials);
            assertThat(resp.getBodyString()).isEqualTo("ok");
        }

        // Add new user
        Properties reloadedProperties = new Properties();
        reloadedProperties.put("user.test2", "test2");
        changeDynamicConfiguration(reloadedProperties);
        correctCredentials = new RawHttpClient.BasicAuthCredentials("test2", "test2");
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/up", correctCredentials);
            assertThat(resp.getBodyString()).isEqualTo("ok");
        }
    }

    @Test
    void cache() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/cache/info", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertThatJson(s).isEqualTo("""
                    {result: "ok", hits: 0, directMemoryUsed: 0, misses: 0,
                     heapMemoryUsed: 0, totalMemoryUsed: 0, cachesize: 0}
                    """);
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/cache/flush", credentials);
            String s = resp.getBodyString();
            System.out.println("s:" + s);
            assertThatJson(s).isEqualTo("{result: 'ok', cachesize: 0}");
        }
    }

    @Test
    void backends() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/backends", credentials);
            String s = resp.getBodyString();
            // no backend configured
            assertThatJson(s).isEqualTo("{}");
        }
    }

    @Test
    void routes() throws Exception {
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        properties.put("route.0.id", "id0");
        properties.put("route.0.action", "id-action");
        properties.put("route.0.enabled", "true");
        properties.put("route.0.match", "all");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/routes", credentials);
            String s = resp.getBodyString();
            assertThatJson(s).when(Option.IGNORING_EXTRA_FIELDS).isArray().contains(json("""
                    {id: "id0", action: "id-action", enabled: true, matcher: "%s"}
                    """.formatted(new MatchAllRequestMatcher().getDescription())));
        }
    }

    @Test
    void actions() throws Exception {
        startAdmin();

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            RawHttpClient.HttpResponse resp = client.get("/api/actions", credentials);
            String s = resp.getBodyString();
            // Default actions
            assertThatJson(s).when(Option.IGNORING_EXTRA_FIELDS).isArray().contains(
                    json("{id: 'not-found', type: 'static'}"),
                    json("{id: 'cache-if-possible', type: 'cache'}"),
                    json("{id: 'internal-error', type: 'static'}"),
                    json("{id: 'proxy-all', type: 'proxy'}"));
        }
    }

    @Test
    void directors() throws Exception {
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
            assertThatJson(resp.getBodyString())
                    .when(Option.IGNORING_EXTRA_FIELDS, Option.IGNORING_ARRAY_ORDER)
                    .isArray()
                    .containsExactly(json("""
                            {
                              "id": "iddirector2",
                              "backends": ["localhost:8086", "localhost:8087"]
                            }"""));
        }
    }

    @Test
    void config() throws Exception {
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
            assertThat(resp.isOk()).isTrue();
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
            assertThat(resp.isError()).isTrue();
            assertThatJson(resp.getBodyString()).isEqualTo(json("""
                    {
                      "message": "Invalid integer value '20-BAD-VALUE' for parameter 'connectionsmanager.maxconnectionsperendpoint'"
                    }"""));
        }
    }

    @Test
    void listeners() throws Exception {
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


            // the map is keyed by "host:port", and only its values were checked before
            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).isObject()
                    .containsOnlyKeys("localhost:1234", "127.0.0.1:9876")
                    .containsValues(
                            json("{host: 'localhost', port: 1234}"),
                            json("{host: '127.0.0.1', port: 9876}"));
        }

    }

    @Test
    void certificates() throws Exception {
        final String dynDomain = "dynamic.test.tld";
        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);

        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        X509Certificate certificate = (X509Certificate) originalChain[0];
        String serialNumber1 = certificate.getSerialNumber().toString(16).toUpperCase();
        String expiringDate1 = certificate.getNotAfter().toString();
        byte[] keystoreData = createKeystore(originalChain, endUserKeyPair.getPrivate());
        File mock1 = newFile(tmpFolder, "mock1.p12");
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
        File mock2 = newFile(tmpFolder, "mock2.p12");
        Files.write(mock2.toPath(), keystoreData);
        properties.put("certificate.2.hostname", "127.0.0.1");
        properties.put("certificate.2.file", mock2.getAbsolutePath());
        properties.put("certificate.2.password", KEYSTORE_PW);

        // Acme certificate
        properties.put("certificate.3.hostname", dynDomain);
        properties.put("certificate.3.mode", "acme");

        // local certificate storing
        File nowrite = newFolder(tmpDir, "nowrite");
        nowrite.setWritable(false);
        properties.put("dynamiccertificatesmanager.localcertificates.store.path", nowrite.getAbsolutePath());
        assertThatThrownBy(() -> startServer(properties)).isInstanceOf(Exception.class);
        nowrite.setWritable(true);

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
        store.saveCertificate(new CertificateData(dynDomain, dynChain, WAITING));
        man.setStateOfCertificate(dynDomain, WAITING); // this reloads certificates from the store

        // Static certificates
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();


            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().contains(
                    json("""
                         {id: "localhost", mode: "static", dynamic: false, status: "available",
                          sslCertificateFile: "%s", serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(mock1.getAbsolutePath(), serialNumber1, expiringDate1)),
                    json("""
                         {id: "127.0.0.1", mode: "static", dynamic: false, status: "expired",
                          sslCertificateFile: "%s", serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(mock2.getAbsolutePath(), serialNumber2, expiringDate2)));

            // single cert request to /{certId}
            response = client.get("/api/certificates/127.0.0.1", credentials);
            json = response.getBodyString();
            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray()
                    .containsExactly(json("""
                                          {id: "127.0.0.1", mode: "static", dynamic: false, status: "expired",
                                           sslCertificateFile: "%s", serialNumber: "%s", expiringDate: "%s"}
                                          """.formatted(mock2.getAbsolutePath(), serialNumber2, expiringDate2)));
        }

        // Acme certificate
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();


            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().contains(
                    json("""
                         {id: "%s", mode: "acme", dynamic: true, status: "waiting",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(dynDomain, serialNumber, expiringDate)));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + dynDomain, credentials);
            json = response.getBodyString();
            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().containsExactly(
                    json("""
                         {id: "%s", mode: "acme", dynamic: true, status: "waiting",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(dynDomain, serialNumber, expiringDate)));

            // Changing dynamic certificate state
            for (DynamicCertificateState state : DynamicCertificateState.values()) {
                man.setStateOfCertificate(dynDomain, state);
                response = client.get("/api/certificates", credentials);
                json = response.getBodyString();
                assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().contains(
                        json("""
                             {id: "%s", mode: "acme", dynamic: true, status: "%s"}
                             """.formatted(dynDomain, certificateStateToString(state))));

                response = client.get("/api/certificates/" + dynDomain, credentials);
                json = response.getBodyString();
                assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().containsExactly(
                        json("""
                             {id: "%s", mode: "acme", dynamic: true, status: "%s"}
                             """.formatted(dynDomain, certificateStateToString(state))));
            }

            // Downloading
            CertificateData cert = store.loadCertificateForDomain(dynDomain);
            byte[] newKeystore = createKeystore(generateSampleChain(endUserKeyPair, false), KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE).getPrivate());
            cert.setChain(Base64.getEncoder().encodeToString(newKeystore));
            store.saveCertificate(cert);
            man.setStateOfCertificate(dynDomain, DynamicCertificateState.AVAILABLE);
            response = client.get("/api/certificates/" + dynDomain + "/download", credentials);
            assertThat(response.getBody()).containsExactly(newKeystore);
        }

        // Manual certificate
        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            String manualDomain = "manual.test.tld";

            int certsCount = server.getCurrentConfiguration().getCertificates().size();

            // Uploading trash-stuff
            RawHttpClient.HttpResponse resp = uploadCertificate(manualDomain, null, "fake-chain".getBytes(), client, credentials);
            String s = resp.getBodyString();
            assertThat(s).contains("ERROR");

            // Uploading real certificate
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            originalChain = generateSampleChain(endUserKeyPair, false);
            certificate = (X509Certificate) originalChain[0];
            serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
            expiringDate = certificate.getNotAfter().toString();
            byte[] chain1 = createKeystore(originalChain, endUserKeyPair.getPrivate());
            resp = uploadCertificate(manualDomain, null, chain1, client, credentials);
            s = resp.getBodyString();
            assertThat(s).contains("SUCCESS");

            int certsCount2 = server.getCurrentConfiguration().getCertificates().size();
            assertThat(certsCount2).isEqualTo(certsCount + 1);

            // full list request
            RawHttpClient.HttpResponse response = client.get("/api/certificates", credentials);
            String json = response.getBodyString();


            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().contains(
                    json("""
                         {id: "%s", mode: "manual", dynamic: true, status: "available",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(manualDomain, serialNumber, expiringDate)));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + manualDomain, credentials);
            json = response.getBodyString();
            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().containsExactly(
                    json("""
                         {id: "%s", mode: "manual", dynamic: true, status: "available",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(manualDomain, serialNumber, expiringDate)));

            // Downloading
            response = client.get("/api/certificates/" + manualDomain + "/download", credentials);
            Certificate[] responseChain = CertificatesUtils.readChainFromKeystore(response.getBody());
            assertThat(responseChain).containsExactly(CertificatesUtils.readChainFromKeystore(chain1));

            // Certificate updating
            // Uploading
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            originalChain = generateSampleChain(endUserKeyPair, true);
            certificate = (X509Certificate) originalChain[0];
            serialNumber = certificate.getSerialNumber().toString(16).toUpperCase();
            expiringDate = certificate.getNotAfter().toString();
            byte[] chain2 = createKeystore(originalChain, endUserKeyPair.getPrivate());
            assertThat(Arrays.equals(chain1, chain2)).isFalse();
            resp = uploadCertificate(manualDomain, null, chain2, client, credentials);
            s = resp.getBodyString();
            assertThat(s).contains("SUCCESS");

            //  check properties (certificate) not duplicated
            int certsCount3 = server.getCurrentConfiguration().getCertificates().size();
            assertThat(certsCount3).isEqualTo(certsCount2);

            // full list request
            response = client.get("/api/certificates", credentials);
            json = response.getBodyString();


            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().contains(
                    json("""
                         {id: "%s", mode: "manual", dynamic: true, status: "expired",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(manualDomain, serialNumber, expiringDate)));

            // single cert request to /{certId}
            response = client.get("/api/certificates/" + manualDomain, credentials);
            json = response.getBodyString();
            assertThatJson(json).when(Option.IGNORING_EXTRA_FIELDS).node("certificates").isArray().containsExactly(
                    json("""
                         {id: "%s", mode: "manual", dynamic: true, status: "expired",
                          serialNumber: "%s", expiringDate: "%s"}
                         """.formatted(manualDomain, serialNumber, expiringDate)));

            // Downloading
            response = client.get("/api/certificates/" + manualDomain + "/download", credentials);
            Certificate[] responseChain2 = CertificatesUtils.readChainFromKeystore(response.getBody());
            assertThat(responseChain2).containsExactly(CertificatesUtils.readChainFromKeystore(chain2));
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void resourcesFilter() throws Exception {
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


            assertThatJson(json).inPath("$[*].type").isArray().containsExactlyInAnyOrder(
                    RegexpMapUserIdFilter.TYPE,
                    RegexpMapSessionIdFilter.TYPE,
                    XForwardedForRequestFilter.TYPE,
                    XTlsProtocolRequestFilter.TYPE,
                    XTlsCipherRequestFilter.TYPE);
            assertThatJson(json).inPath("$[*].values.parameterName").isArray()
                    .contains("param_test_user", "param_test_session");
        }
    }

    @Test
    void userRealm() throws Exception {
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


            assertThatJson(json).isArray().containsExactlyInAnyOrder("test", "test1", "test2");
        }
    }

    @Test
    void httpsApi() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir);

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

            assertThat(json).contains("https.admin.sslcertfile=" + certificate);
        }

        IOException exc = null;
        try (RawHttpClient client = new RawHttpClient("localhost", 8762, false)) {
            client.get("/api/config", credentials);
        } catch (IOException ex) {
            exc = ex;
        }

        assertThat(exc).isNotNull();
        assertThat(exc.getMessage()).contains("bad response, does not start with HTTP/1.1");
    }

    @Test
    void httpAndHttpsApi() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir);

        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        properties.setProperty("https.admin.port", "8762");
        properties.setProperty("https.admin.sslcertfile", certificate);
        properties.setProperty("https.admin.sslcertfilepassword", "testproxy");

        startServer(properties);

        try (RawHttpClient client = new RawHttpClient("localhost", 8762, true)) {
            RawHttpClient.HttpResponse response = client.get("/api/config", credentials);
            String json = response.getBodyString();

            assertThat(json).contains("https.admin.sslcertfile=" + certificate);
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761, false)) {
            RawHttpClient.HttpResponse response = client.get("/api/config", credentials);
            String json = response.getBodyString();

            assertThat(json).contains("https.admin.sslcertfile=" + certificate);
        }
    }

    @Test
    void apiRequestsLogger() throws Exception {
        String certificate = TestUtils.deployResource("localhost.p12", tmpDir);

        Properties properties = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        properties.setProperty("https.admin.port", "8762");
        properties.setProperty("https.admin.sslcertfile", certificate);
        properties.setProperty("https.admin.sslcertfilepassword", "testproxy");

        File accessLog = File.createTempFile("junit", null, tmpDir).getAbsoluteFile();
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
                assertThat(line).contains("\"GET /api/config HTTP/1.1\" 200");
                lineCount++;
            }

            assertThat(lineCount).isEqualTo(2);
        }
    }

    private static File newFile(File parent, String child) throws IOException {
        File result = new File(parent, child);
        result.createNewFile();
        return result;
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs) + "-" + System.nanoTime();
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

}
