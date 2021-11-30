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
package org.carapaceproxy.server.certificates;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.api.UseAdminServer.DEFAULT_ADMIN_PORT;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.Properties;
import org.carapaceproxy.api.UseAdminServer;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.shredzone.acme4j.Status.VALID;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.util.KeyPairUtils;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.junit.Assert.assertTrue;
import java.util.Base64;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.powermock.reflect.Whitebox;

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
        HttpTestUtils.overideJvmWideHttpsVerifier();

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
        config.put("listener.1.ocsp", "true");
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
        config.put("route.100.match", "all");
        config.put("route.100.action", "proxy-all");

        changeDynamicConfiguration(config);
    }

    /**
     * Test case: - Start server with a default certificate - Make request expected default certificate
     *
     * - Add a manual certificate to config (without upload) - Make request expected default certificate
     *
     * - Upload the certificate - Make request expected uploaded certificate
     *
     * - Update the certificate - Make request expected updated certificate
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
            assertTrue(chain0[0].equals(chain1[0]));
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
            assertTrue(data.getState() == DynamicCertificateState.AVAILABLE);
        }

        // Request #2: expected uploaded certificate
        Certificate[] chain2;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain2 = client.getServerCertificate();
            assertNotNull(chain2);
            assertTrue(uploadedChain[0].equals(chain2[0]));
        }

        // Update manual certificate
        Certificate[] uploadedChain2;
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            uploadedChain2 = generateSampleChain(endUserKeyPair, false);
            assertFalse(uploadedChain[0].equals(uploadedChain2[0]));
            byte[] chainData = createKeystore(uploadedChain2, endUserKeyPair.getPrivate());
            HttpResponse resp = uploadCertificate("localhost", null, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            data = dynCertMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertTrue(data.isManual());
            assertTrue(data.getState() == DynamicCertificateState.AVAILABLE);
        }

        // Request #3: expected last uploaded certificate
        Certificate[] chain3;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain3 = client.getServerCertificate();
            assertNotNull(chain3);
            assertTrue(uploadedChain2[0].equals(chain3[0]));
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
                assertTrue(chain[0].equals(obtainedChain[0]));
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
                assertTrue(chain[0].equals(obtainedChain[0]));
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
            assertTrue(data.getState() == DynamicCertificateState.AVAILABLE);
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
                assertTrue(chain1[0].equals(obtainedChain[0]));
            }
        }

        // Renew
        KeyPair keyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);

        ConfigurationStore store = dcMan.getConfigurationStore();
        store.saveKeyPairForDomain(keyPair, "localhost", false);
        CertificateData cert = dcMan.getCertificateDataForDomain("localhost");
        cert.setState(DynamicCertificateState.ORDERING);
        cert.setPendingOrderLocation("https://localhost/orderlocation");
        cert = dcMan.getCertificateDataForDomain("localhost");
        assertNotNull(cert);
        assertEquals(DynamicCertificateState.ORDERING, cert.getState());

        // ACME mocking
        ACMEClient ac = mock(ACMEClient.class);
        Order o = mock(Order.class);
        when(ac.getLogin()).thenReturn(mock(Login.class));
        when(ac.checkResponseForOrder(any())).thenReturn(VALID);
        org.shredzone.acme4j.Certificate _cert = mock(org.shredzone.acme4j.Certificate.class);
        X509Certificate renewed = (X509Certificate) generateSampleChain(keyPair, false)[0];
        when(_cert.getCertificateChain()).thenReturn(Arrays.asList(renewed));
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
            assertTrue(renewed.equals(obtainedChain[0]));
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
            byte[] chainData = createKeystore(chain, endUserKeyPair.getPrivate());
            String chain1 = Base64.getEncoder().encodeToString(chainData);
            uploadCertificate("localhost2", null, chainData, client, credentials);
            CertificateData data = dynCertsMan.getCertificateDataForDomain("localhost2");
            assertNotNull(data);
            assertEquals(chain1, data.getChain());
            assertFalse(data.isWildcard());

            // upload wildcard
            endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            chain = generateSampleChain(endUserKeyPair, false);
            chainData = createKeystore(chain, endUserKeyPair.getPrivate());
            String chain2 = Base64.getEncoder().encodeToString(chainData);
            uploadCertificate("*.localhost2", null, chainData, client, credentials);
            data = dynCertsMan.getCertificateDataForDomain("*.localhost2");
            assertNotNull(data);
            assertEquals(chain2, data.getChain());
            assertTrue(data.isWildcard());

            // exact still different from wildcard
            data = dynCertsMan.getCertificateDataForDomain("localhost2");
            assertNotNull(data);
            assertEquals(chain1, data.getChain());
            assertFalse(data.isWildcard());
        }
    }

}
