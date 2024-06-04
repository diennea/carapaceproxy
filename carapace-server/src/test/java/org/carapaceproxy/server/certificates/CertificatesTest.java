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
package org.carapaceproxy.server.certificates;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.shredzone.acme4j.Status.VALID;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.ExtendedSSLSession;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.carapaceproxy.api.CertificatesResource;
import org.carapaceproxy.api.UseAdminServer;
import org.carapaceproxy.api.response.FormValidationResponse;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 * Test use cases for basic certificates management and client requests.
 *
 * @author paolo.venturi
 */
@RunWith(JUnitParamsRunner.class)
public class CertificatesTest extends UseAdminServer {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    private Properties config;

    private void configureAndStartServer() throws Exception {
        HttpTestUtils.overrideJvmWideHttpsVerifier();

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("config.type", "database");
        config.put("db.jdbc.url", "jdbc:herddb:localhost");
        config.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        config.put("aws.accesskey", "accesskey");
        config.put("aws.secretkey", "secretkey");
        startServer(config);

        // Default certificate
        String defaultCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        config.put("certificate.1.hostname", "*");
        config.put("certificate.1.file", defaultCertificate);
        config.put("certificate.1.password", "changeit");

        // SSL Listener
        config.put("listener.1.host", "localhost");
        config.put("listener.1.port", "8443");
        config.put("listener.1.ssl", "true");
        config.put("listener.1.enabled", "true");
        config.put("listener.1.defaultcertificate", "*");

        // Backend
        config.put("backend.1.id", "localhost");
        config.put("backend.1.enabled", "true");
        config.put("backend.1.host", "localhost");
        config.put("backend.1.port", wireMockRule.port() + "");

        // Default director
        config.put("director.1.id", "*");
        config.put("director.1.backends", "*");
        config.put("director.1.enabled", "true");

        // Default route
        config.put("route.100.id", "default");
        config.put("route.100.enabled", "true");
        config.put("route.100.action", "proxy-all");
        config.put("route.100.match", "all");

        //Enabled ocsp stapling
        config.put("ocsp.enabled", "true");

        changeDynamicConfiguration(config);
    }

    /**
     * Test case:
     * - Start server with a default certificate
     * - Make request expected default certificate
     * - Add a manual certificate to config (without upload)
     * - Make request expected default certificate
     * - Upload the certificate
     * - Make request expected uploaded certificate
     * - Update the certificate
     * - Make request expected updated certificate
     */
    @Test
    public void test() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();

        // Request #0: expected default certificate
        Certificate[] chain0;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain0 = client.getServerCertificate();
            assertNotNull(chain0);
        }

        // Update settings adding manual certificate (but without upload it)
        config.put("certificate.2.hostname", "localhost");
        config.put("certificate.2.mode", "manual");
        changeDynamicConfiguration(config);

        DynamicCertificatesManager dynCertMan = server.getDynamicCertificatesManager();
        CertificateData data = dynCertMan.getCertificateDataForDomain("localhost");
        assertNotNull(data);
        assertTrue(data.isManual());

        // Request #1: still expected default certificate
        Certificate[] chain1;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain1 = client.getServerCertificate();
            assertNotNull(chain1);
            assertEquals(chain0[0], chain1[0]);
        }

        // Upload manual certificate
        Certificate[] uploadedChain;
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            uploadedChain = generateSampleChain(endUserKeyPair, false);
            byte[] chainData = createKeystore(uploadedChain, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", null, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            data = dynCertMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertTrue(data.isManual());
            assertSame(data.getState(), DynamicCertificateState.AVAILABLE);
        }

        // Request #2: expected uploaded certificate
        Certificate[] chain2;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain2 = client.getServerCertificate();
            assertNotNull(chain2);
            assertEquals(uploadedChain[0], chain2[0]);
        }

        // Update manual certificate
        Certificate[] uploadedChain2;
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            uploadedChain2 = generateSampleChain(endUserKeyPair, false);
            assertNotEquals(uploadedChain[0], uploadedChain2[0]);
            byte[] chainData = createKeystore(uploadedChain2, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", null, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            data = dynCertMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertTrue(data.isManual());
            assertSame(data.getState(), DynamicCertificateState.AVAILABLE);
        }

        // Request #3: expected last uploaded certificate
        Certificate[] chain3;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain3 = client.getServerCertificate();
            assertNotNull(chain3);
            assertEquals(uploadedChain2[0], chain3[0]);
        }

        // this calls "reloadFromDB" > "manual" flag has to be retained even if not stored in db.
        dynCertMan.setStateOfCertificate("localhost", DynamicCertificateState.WAITING);
        data = dynCertMan.getCertificateDataForDomain("localhost");
        assertNotNull(data);
        assertTrue(data.isManual());
    }

    @Test
    @Parameters({"acme", "manual"})
    public void testUploadTypedCertificate(String type) throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        DynamicCertificatesManager dynCertsMan = server.getDynamicCertificatesManager();

        // Uploading certificate without data:
        // - for type="acme" means creating an order for an ACME certificate
        // - for type="manual" is forbidden
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            HttpResponse resp = uploadCertificate("localhost", "type=" + type, new byte[0], client, credentials);
            if (type.equals("manual")) {
                assertTrue(resp.getBodyString().contains("ERROR: certificate data required"));
            } else {
                CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost");
                assertNotNull(data);
                assertFalse(data.isManual());
                assertEquals(DynamicCertificateState.WAITING, dynCertsMan.getStateOfCertificate("localhost")); // no certificate-data uploaded
            }
        }

        // Uploading certificate with data
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, false);
            byte[] chainData = createKeystore(chain, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", "type=" + type, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertEquals(type.equals("manual"), data.isManual());
            assertEquals(DynamicCertificateState.AVAILABLE, dynCertsMan.getStateOfCertificate("localhost"));

            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("/index.html", credentials);
                assertEquals("it <b>works</b> !!", r.getBodyString());
                Certificate[] obtainedChain = c.getServerCertificate();
                assertNotNull(obtainedChain);
                assertEquals(chain[0], obtainedChain[0]);
            }
        }

        // Uploading trush: bad "type"
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            HttpResponse resp = uploadCertificate("localhost", "type=undefined", new byte[0], client, credentials);
            assertTrue(resp.getBodyString().contains("ERROR: illegal type"));
        }

        // Uploading same certificate but with different type (will be update)
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            String otherType = type.equals("manual") ? "acme" : "manual";
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, false);
            byte[] chainData = createKeystore(chain, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", "type=" + otherType, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertEquals(otherType.equals("manual"), data.isManual());
            assertEquals(DynamicCertificateState.AVAILABLE, dynCertsMan.getStateOfCertificate("localhost"));

            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("/index.html", credentials);
                assertEquals("it <b>works</b> !!", r.getBodyString());
                Certificate[] obtainedChain = c.getServerCertificate();
                assertNotNull(obtainedChain);
                assertEquals(chain[0], obtainedChain[0]);
            }

            resp = uploadCertificate("localhost", "type=" + type, new byte[0], client, credentials);
            if (type.equals("acme")) {
                assertTrue(resp.getBodyString().contains("SUCCESS"));
                data = dynCertsMan.getCertificateDataForDomain("localhost");
                assertNotNull(data);
                assertFalse(data.isManual());
                assertEquals(DynamicCertificateState.WAITING, dynCertsMan.getStateOfCertificate("localhost")); // no certificate-data uploaded
            }
        }
    }

    @Test
    @Parameters({"acme", "manual"})
    public void testUploadTypedCertificatesWithDaysBeforeRenewal(String type) throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        DynamicCertificatesManager dynCertsMan = server.getDynamicCertificatesManager();
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] chain = generateSampleChain(endUserKeyPair, false);
        byte[] chainData = createKeystore(chain, endUserKeyPair.getPrivate());

        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // Create
            HttpResponse resp = uploadCertificate("localhost2", "type=" + type + "&daysbeforerenewal=10", chainData, client, credentials);
            if (type.equals("manual")) {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' available for type 'acme' only"));
            } else {
                CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost2");
                assertNotNull(data);
                assertEquals(10, data.getDaysBeforeRenewal());
            }
            // negative value
            resp = uploadCertificate("localhost-negative", "type=" + type + "&daysbeforerenewal=-10", chainData, client, credentials);
            if (type.equals("manual")) {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' available for type 'acme' only"));
            } else {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' has to be a positive number"));
            }
            // default value
            uploadCertificate("localhost-default", "type=" + type, chainData, client, credentials);
            CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost-default");
            assertNotNull(data);
            assertEquals(type.equals("manual") ? 0 : DEFAULT_DAYS_BEFORE_RENEWAL, data.getDaysBeforeRenewal());

            // Update
            uploadCertificate("localhost2", "type=" + type + "&daysbeforerenewal=45", chainData, client, credentials);
            if (type.equals("manual")) {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' available for type 'acme' only"));
            } else {
                data = dynCertsMan.getCertificateDataForDomain("localhost2");
                assertNotNull(data);
                assertEquals(45, data.getDaysBeforeRenewal());
            }
            // negative value
            resp = uploadCertificate("localhost2", "type=" + type + "&daysbeforerenewal=-10", chainData, client, credentials);
            if (type.equals("manual")) {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' available for type 'acme' only"));
            } else {
                assertTrue(resp.getBodyString().contains("ERROR: param 'daysbeforerenewal' has to be a positive number"));
            }
            // default value
            uploadCertificate("localhost2", "type=" + type, chainData, client, credentials);
            data = dynCertsMan.getCertificateDataForDomain("localhost2");
            assertNotNull(data);
            assertEquals(type.equals("manual") ? 0 : DEFAULT_DAYS_BEFORE_RENEWAL, data.getDaysBeforeRenewal());
            // changing the type (acme <-> manual)
            String other = type.equals("manual") ? "acme" : "manual";
            uploadCertificate("localhost2", "type=" + other, chainData, client, credentials);
            data = dynCertsMan.getCertificateDataForDomain("localhost2");
            assertNotNull(data);
            assertEquals(other.equals("manual") ? 0 : DEFAULT_DAYS_BEFORE_RENEWAL, data.getDaysBeforeRenewal());
            SSLCertificateConfiguration config = server.getCurrentConfiguration().getCertificates().get("localhost2");
            assertEquals(other.equals("manual") ? 0 : DEFAULT_DAYS_BEFORE_RENEWAL, config.getDaysBeforeRenewal());
            // checking for "certificate.X.daysbeforerenewal" property delete
            ConfigurationStore store = server.getDynamicConfigurationStore();
            assertEquals(other.equals("acme"), store.anyPropertyMatches((k, v) -> {
                if (k.matches("certificate\\.[0-9]+\\.hostname") && v.equals("localhost2")) {
                    return store.getProperty(k.replace("hostname", "daysbeforerenewal"), null) != null;
                }
                return false;
            }));
        }
    }

    @Test
    public void testOCSP() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        OcspStaplingManager ocspMan = mock(OcspStaplingManager.class);
        server.setOcspStaplingManager(ocspMan);
        DynamicCertificatesManager dynCertMan = server.getDynamicCertificatesManager();

        // Upload certificate and check its OCSP response
        Certificate[] uploadedChain;
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        uploadedChain = generateSampleChain(endUserKeyPair, false);
        OCSPResp ocspResp = generateOCSPResponse(uploadedChain, CertificateStatus.GOOD);
        when(ocspMan.getOcspResponseForCertificate(uploadedChain[0])).thenReturn(ocspResp.getEncoded());
        byte[] chainData = createKeystore(uploadedChain, endUserKeyPair.getPrivate());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            RawHttpClient.HttpResponse resp = uploadCertificate("localhost", null, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dynCertMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertTrue(data.isManual());
            assertSame(data.getState(), DynamicCertificateState.AVAILABLE);
        }
        // check ocsp response
        try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse r = c.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", r.getBodyString());
            Certificate[] obtainedChain = c.getServerCertificate();
            assertNotNull(obtainedChain);
            CertificatesUtils.compareChains(uploadedChain, obtainedChain);
            ExtendedSSLSession session = (ExtendedSSLSession) c.getSSLSocket().getSession();
            List<byte[]> statusResponses = session.getStatusResponses();
            assertEquals(1, statusResponses.size());
        }
    }

    private static OCSPResp generateOCSPResponse(Certificate[] chain, CertificateStatus status) throws CertificateException {
        try {
            X509Certificate cert = (X509Certificate) chain[0];
            X509Certificate issuer = (X509Certificate) chain[chain.length - 1];
            X509CertificateHolder caCert = new JcaX509CertificateHolder(issuer);

            DigestCalculatorProvider digCalcProv = new BcDigestCalculatorProvider();
            BasicOCSPRespBuilder basicBuilder = new BasicOCSPRespBuilder(
                    SubjectPublicKeyInfo.getInstance(issuer.getPublicKey().getEncoded()),
                    digCalcProv.get(CertificateID.HASH_SHA1)
            );

            CertificateID certId = new CertificateID(digCalcProv.get(CertificateID.HASH_SHA1), caCert, cert.getSerialNumber());
            basicBuilder.addResponse(certId, status);
            BasicOCSPResp resp = basicBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").build(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE).getPrivate()), null, new Date());

            OCSPRespBuilder builder = new OCSPRespBuilder();
            return builder.build(OCSPRespBuilder.SUCCESSFUL, resp);
        } catch (Exception e) {
            throw new CertificateException("cannot generate OCSP response", e);
        }
    }

    @Test
    public void testCertificatesRenew() throws Exception {

        configureAndStartServer();
        int port = server.getLocalPort();
        DynamicCertificatesManager dcMan = server.getDynamicCertificatesManager();
        dcMan.setPeriod(0);

        // Uploading ACME certificate with data
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] chain1 = generateSampleChain(endUserKeyPair, false);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            byte[] chainData = createKeystore(chain1, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", "type=acme&daysbeforerenewal=45", chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dcMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertEquals(DynamicCertificateState.AVAILABLE, data.getState());
            assertEquals(45, data.getDaysBeforeRenewal());
            assertFalse(data.isManual());
            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("/index.html", credentials);
                assertEquals("it <b>works</b> !!", r.getBodyString());
                Certificate[] obtainedChain = c.getServerCertificate();
                assertNotNull(obtainedChain);
                assertEquals(chain1[0], obtainedChain[0]);
            }
        }

        // Renew
        KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);

        ConfigurationStore store = dcMan.getConfigurationStore();
        store.saveKeyPairForDomain(keyPair, "localhost", false);
        CertificateData cert = dcMan.getCertificateDataForDomain("localhost");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation(new URL("https://localhost/orderlocation"));
        store.saveCertificate(cert);
        assertEquals(DynamicCertificateState.ORDERING, dcMan.getStateOfCertificate("localhost"));

        // ACME mocking
        ACMEClient ac = mock(ACMEClient.class);
        when(ac.getLogin()).thenReturn(mock(Login.class));
        when(ac.checkResponseForOrder(any())).thenReturn(VALID);
        org.shredzone.acme4j.Certificate _cert = mock(org.shredzone.acme4j.Certificate.class);
        List<X509Certificate> renewed = Arrays.asList((X509Certificate[]) generateSampleChain(keyPair, false));
        when(_cert.getCertificateChain()).thenReturn(renewed);
        when(ac.fetchCertificateForOrder(any())).thenReturn(_cert);
        Whitebox.setInternalState(dcMan, ac);

        // Renew
        dcMan.run();
        CertificateData updated = dcMan.getCertificateDataForDomain("localhost");
        assertNotNull(updated);
        assertEquals(DynamicCertificateState.AVAILABLE, updated.getState());
        assertEquals(45, updated.getDaysBeforeRenewal());
        assertFalse(updated.isManual());

        // Check renewed certificate
        try (RawHttpClient cl = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse r = cl.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", r.getBodyString());
            Certificate[] obtainedChain = cl.getServerCertificate();
            assertNotNull(obtainedChain);
            assertEquals(renewed.get(0), obtainedChain[0]);
        }
    }

    @Test
    public void testWildcardsCertificates() throws Exception {
        configureAndStartServer();
        DynamicCertificatesManager dynCertsMan = server.getDynamicCertificatesManager();

        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            // upload exact
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, false);
            byte[] chainData1 = createKeystore(chain, endUserKeyPair.getPrivate());
            uploadCertificate("localhost2", null, chainData1, client, credentials);
            CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost2");
            Certificate[] saveChain = base64DecodeCertificateChain(data.getChain());
            assertNotNull(data);
            assertArrayEquals(CertificatesUtils.readChainFromKeystore(chainData1), saveChain);

            // upload wildcard
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            chain = generateSampleChain(endUserKeyPair, false);
            byte[] chainData2 = createKeystore(chain, endUserKeyPair.getPrivate());
            uploadCertificate("*.localhost2", null, chainData2, client, credentials);
            data = dynCertsMan.getCertificateDataForDomain("*.localhost2");
            Certificate[] saveChain2 = base64DecodeCertificateChain(data.getChain());
            assertNotNull(data);
            assertArrayEquals(CertificatesUtils.readChainFromKeystore(chainData2), saveChain2);

            // exact still different from wildcard
            data = dynCertsMan.getCertificateDataForDomain("localhost2");
            Certificate[] saveChain3 = base64DecodeCertificateChain(data.getChain());
            assertNotNull(data);
            assertArrayEquals(CertificatesUtils.readChainFromKeystore(chainData1), saveChain3);
        }
    }

    @Test
    public void testLocalCertificatesStoring() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        DynamicCertificatesManager dcMan = server.getDynamicCertificatesManager();
        dcMan.setPeriod(0);

        // Uploading ACME certificate with data
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] chain1 = generateSampleChain(endUserKeyPair, false);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            byte[] chainData = createKeystore(chain1, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", "type=acme&daysbeforerenewal=45", chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dcMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertEquals(DynamicCertificateState.AVAILABLE, data.getState());
            assertEquals(45, data.getDaysBeforeRenewal());
            assertFalse(data.isManual());
            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("/index.html", credentials);
                assertEquals("it <b>works</b> !!", r.getBodyString());
                Certificate[] obtainedChain = c.getServerCertificate();
                assertNotNull(obtainedChain);
                assertEquals(chain1[0], obtainedChain[0]);
            }
        }

        // Renew
        KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);

        ConfigurationStore store = dcMan.getConfigurationStore();
        store.saveKeyPairForDomain(keyPair, "localhost", false);
        CertificateData cert = dcMan.getCertificateDataForDomain("localhost");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation(new URL("https://localhost/orderlocation"));
        store.saveCertificate(cert);
        assertEquals(DynamicCertificateState.ORDERING, dcMan.getStateOfCertificate("localhost"));

        // ACME mocking
        ACMEClient ac = mock(ACMEClient.class);
        when(ac.getLogin()).thenReturn(mock(Login.class));
        when(ac.checkResponseForOrder(any())).thenReturn(VALID);
        org.shredzone.acme4j.Certificate _cert = mock(org.shredzone.acme4j.Certificate.class);
        List<X509Certificate> renewed = Arrays.asList((X509Certificate[]) generateSampleChain(keyPair, false));
        when(_cert.getCertificateChain()).thenReturn(renewed);
        when(ac.fetchCertificateForOrder(any())).thenReturn(_cert);
        Whitebox.setInternalState(dcMan, ac);

        // Renew
        File certsDir = tmpDir.newFolder("certs");
        server.getCurrentConfiguration().setLocalCertificatesStorePath(certsDir.getAbsolutePath());
        server.getCurrentConfiguration().setLocalCertificatesStorePeersIds(Set.of("peerPippo")); // storing enabled on fake peer only
        dcMan.run();
        CertificateData updated = dcMan.getCertificateDataForDomain("localhost");
        assertNotNull(updated);
        assertEquals(DynamicCertificateState.AVAILABLE, updated.getState());
        assertEquals(45, updated.getDaysBeforeRenewal());
        assertFalse(updated.isManual());

        // Check renewed certificate
        try (RawHttpClient cl = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse r = cl.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", r.getBodyString());
            Certificate[] obtainedChain = cl.getServerCertificate();
            assertNotNull(obtainedChain);
            assertEquals(renewed.get(0), obtainedChain[0]);
        }

        // local certificate path
        File[] f = certsDir.listFiles((dir, name) -> name.equals("localhost"));
        assertEquals(0, f.length);

        cert = dcMan.getCertificateDataForDomain("localhost");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation(new URL("https://localhost/orderlocation"));
        store.saveCertificate(cert);
        assertEquals(DynamicCertificateState.ORDERING, dcMan.getStateOfCertificate("localhost"));
        server.getCurrentConfiguration().setLocalCertificatesStorePeersIds(Set.of(server.getPeerId()));
        dcMan.run();

        f = certsDir.listFiles((dir, name) -> name.equals("localhost"));
        assertEquals(1, f.length);
        assertTrue(f[0].isDirectory());
        File localhostDir = f[0];

        f = localhostDir.listFiles((dir, name) -> name.equals("privatekey.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        String pkPem = Files.readString(f[0].toPath());
        System.out.println("[PRIVARE KEY]: " + pkPem);
        var sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            writer.writeObject(new PemObject("", keyPair.getPrivate().getEncoded()));
        }
        assertEquals(sw.toString(), pkPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("chain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        String chainPem = Files.readString(f[0].toPath());
        System.out.println("[CHAIN]: " + chainPem);
        sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            for (int i = 1; i < renewed.size(); i++) {
                writer.writeObject(new PemObject("", renewed.get(i).getEncoded()));
            }
        }
        assertEquals(sw.toString(), chainPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("fullchain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        String fullchainPem = Files.readString(f[0].toPath());
        System.out.println("[FULLCHAIN]: " + fullchainPem);
        assertTrue(fullchainPem.endsWith(chainPem));

        // Renew 2
        keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        store.saveKeyPairForDomain(keyPair, "localhost", true);
        cert = dcMan.getCertificateDataForDomain("localhost");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation(new URL("https://localhost/orderlocation"));
        store.saveCertificate(cert);
        assertEquals(DynamicCertificateState.ORDERING, dcMan.getStateOfCertificate("localhost"));
        renewed = Arrays.asList((X509Certificate[]) generateSampleChain(keyPair, false));
        when(_cert.getCertificateChain()).thenReturn(renewed);
        dcMan.run();
        updated = dcMan.getCertificateDataForDomain("localhost");
        assertNotNull(updated);
        assertEquals(DynamicCertificateState.AVAILABLE, updated.getState());
        assertEquals(45, updated.getDaysBeforeRenewal());
        assertFalse(updated.isManual());
        // Check renewed certificate
        try (RawHttpClient cl = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse r = cl.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", r.getBodyString());
            Certificate[] obtainedChain = cl.getServerCertificate();
            assertNotNull(obtainedChain);
            assertEquals(renewed.get(0), obtainedChain[0]);
        }

        // local certificate path
        f = certsDir.listFiles((dir, name) -> name.equals("localhost"));
        assertEquals(1, f.length);
        assertTrue(f[0].isDirectory());
        localhostDir = f[0];

        f = localhostDir.listFiles((dir, name) -> name.equals("privatekey.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        pkPem = Files.readString(f[0].toPath());
        System.out.println("[PRIVARE KEY]: " + pkPem);
        sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            writer.writeObject(new PemObject("", keyPair.getPrivate().getEncoded()));
        }
        assertEquals(sw.toString(), pkPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("chain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        chainPem = Files.readString(f[0].toPath());
        System.out.println("[CHAIN]: " + chainPem);
        sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            for (int i = 1; i < renewed.size(); i++) {
                writer.writeObject(new PemObject("", renewed.get(i).getEncoded()));
            }
        }
        assertEquals(sw.toString(), chainPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("fullchain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        fullchainPem = Files.readString(f[0].toPath());
        System.out.println("[FULLCHAIN]: " + fullchainPem);
        assertTrue(fullchainPem.endsWith(chainPem));

        // other cert
        endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] chain2 = generateSampleChain(endUserKeyPair, false);
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            byte[] chainData = createKeystore(chain2, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost2", "type=acme&daysbeforerenewal=45", chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dcMan.getCertificateDataForDomain("localhost2");
            assertNotNull(data);
            assertEquals(DynamicCertificateState.AVAILABLE, data.getState());
            assertEquals(45, data.getDaysBeforeRenewal());
            assertFalse(data.isManual());
        }

        // Renew
        keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);

        store.saveKeyPairForDomain(keyPair, "localhost2", false);
        cert = dcMan.getCertificateDataForDomain("localhost2");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation(new URL("https://localhost/orderlocation"));
        store.saveCertificate(cert);
        assertEquals(DynamicCertificateState.ORDERING, dcMan.getStateOfCertificate("localhost2"));

        // ACME mocking
        renewed = Arrays.asList((X509Certificate[]) generateSampleChain(keyPair, false));
        when(_cert.getCertificateChain()).thenReturn(renewed);

        server.getCurrentConfiguration().setLocalCertificatesStorePath(certsDir.getAbsolutePath());
        dcMan.run();
        updated = dcMan.getCertificateDataForDomain("localhost2");
        assertNotNull(updated);
        assertEquals(DynamicCertificateState.AVAILABLE, updated.getState());
        assertEquals(45, updated.getDaysBeforeRenewal());
        assertFalse(updated.isManual());

        // local certificate path
        assertEquals(1, certsDir.listFiles((dir, name) -> name.equals("localhost")).length);
        f = certsDir.listFiles((dir, name) -> name.equals("localhost2"));
        assertEquals(1, f.length);
        assertTrue(f[0].isDirectory());
        localhostDir = f[0];

        f = localhostDir.listFiles((dir, name) -> name.equals("privatekey.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        pkPem = Files.readString(f[0].toPath());
        System.out.println("[PRIVARE KEY]: " + pkPem);
        sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            writer.writeObject(new PemObject("", keyPair.getPrivate().getEncoded()));
        }
        assertEquals(sw.toString(), pkPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("chain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        chainPem = Files.readString(f[0].toPath());
        System.out.println("[CHAIN]: " + chainPem);
        sw = new StringWriter();
        try (var writer = new PemWriter(sw)) {
            for (int i = 1; i < renewed.size(); i++) {
                writer.writeObject(new PemObject("", renewed.get(i).getEncoded()));
            }
        }
        assertEquals(sw.toString(), chainPem);

        f = localhostDir.listFiles((dir, name) -> name.equals("fullchain.pem"));
        assertEquals(1, f.length);
        assertTrue(f[0].isFile());
        fullchainPem = Files.readString(f[0].toPath());
        System.out.println("[FULLCHAIN]: " + fullchainPem);
        assertTrue(fullchainPem.endsWith(chainPem));
    }

    @Test
    public void testCreateCertificateFromUI() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            final var form = new CertificatesResource.CertificateForm();

            // empty domain name
            var resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isError());
            var result = resp.getData(FormValidationResponse.class);
            assertEquals("domain", result.getField());
            assertEquals(FormValidationResponse.ERROR_FIELD_REQUIRED, result.getMessage());

            // domain name in subject alternative names
            form.setDomain("test.domain.tld");
            form.setSubjectAltNames(Set.of("test.domain.tld", "test2.domain.tld"));
            resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isError());
            result = resp.getData(FormValidationResponse.class);
            assertEquals("subjectAltNames", result.getField());
            assertEquals("Subject alternative names cannot include the Domain", result.getMessage());

            // invalid certificate type
            form.setDomain("test.domain.tld");
            form.setSubjectAltNames(Set.of("test1.domain.tld", "test2.domain.tld"));
            form.setType("manual");
            resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isError());
            result = resp.getData(FormValidationResponse.class);
            assertEquals("type", result.getField());
            assertEquals(FormValidationResponse.ERROR_FIELD_INVALID, result.getMessage());

            // invalid days before renewal
            form.setDomain("test.domain.tld");
            form.setSubjectAltNames(Set.of("test1.domain.tld", "test2.domain.tld"));
            form.setType("acme");
            form.setDaysBeforeRenewal(-1);
            resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isError());
            result = resp.getData(FormValidationResponse.class);
            assertEquals("daysBeforeRenewal", result.getField());
            assertEquals(FormValidationResponse.ERROR_FIELD_INVALID, result.getMessage());

            // all ok
            form.setDomain("test.domain.tld");
            form.setSubjectAltNames(Set.of("test1.domain.tld", "test2.domain.tld"));
            form.setType("acme");
            form.setDaysBeforeRenewal(10);
            resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isCreated());
            CertificateData data = server.getDynamicCertificatesManager().getCertificateDataForDomain("test.domain.tld");
            assertNotNull(data);
            ConfigurationStore store = server.getDynamicConfigurationStore();
            final var indices = getCertificateIndicesWithHostname(store, "test.domain.tld");
            for (final var index : indices) {
                assertThat(store.getProperty("certificate." + index + ".mode", null), is("acme"));
                assertThat(store.getValues("certificate." + index + ".san"), is(Set.of("test1.domain.tld", "test2.domain.tld")));
                assertThat(store.getProperty("certificate." + index +".daysbeforerenewal", null), is("10"));
            }

            // domain name already used
            form.setDomain("test.domain.tld");
            form.setSubjectAltNames(Set.of("test1.domain.tld", "test2.domain.tld"));
            form.setType("acme");
            form.setDaysBeforeRenewal(10);
            resp = client.post("/api/certificates/", null, form, credentials);
            assertTrue(resp.isConflict());
            result = resp.getData(FormValidationResponse.class);
            assertEquals("domain", result.getField());
            assertEquals(FormValidationResponse.ERROR_FIELD_DUPLICATED, result.getMessage());

            resp = client.delete("/api/certificates/test.domain.tld", credentials);
            assertTrue(resp.isOk());
        }
    }

    private static Set<Integer> getCertificateIndicesWithHostname(final ConfigurationStore store, final String hostname) {
        return store.asProperties("certificate").entrySet().stream()
                .map(entry -> Map.entry(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                .filter(entry -> entry.getKey().matches("[0-9]+\\.hostname"))
                .filter(entry -> entry.getValue().matches(hostname))
                .map(entry -> Integer.parseInt(entry.getKey().split("\\.")[0]))
                .collect(Collectors.toUnmodifiableSet());
    }
}
