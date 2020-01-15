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
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.Properties;
import org.carapaceproxy.api.UseAdminServer;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesTestUtils.uploadCertificate;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
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
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
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
            RawHttpClient.HttpResponse resp = client.get("https://localhost:" + port + "/index.html", credentials);
            assertEquals("it <b>works</b> !!", resp.getBodyString());
            chain0 = client.getServerCertificate();
            assertNotNull(chain0);
        }

        // Update settings adding manual certificate (but without upload it)
        config.put("certificate.2.hostname", "localhost");
        config.put("certificate.2.mode", "manual");
        changeDynamicConfiguration(config);

        DynamicCertificatesManager dynCertMan = server.getDynamicCertificateManager();
        CertificateData data = dynCertMan.getCertificateDataForDomain("localhost");
        assertNotNull(data);
        assertTrue(data.isManual());

        // Request #1: still expected default certificate
        Certificate[] chain1;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("https://localhost:" + port + "/index.html", credentials);
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
            assertTrue(data.isAvailable());
        }

        // Request #2: expected uploaded certificate
        Certificate[] chain2;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("https://localhost:" + port + "/index.html", credentials);
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
            assertTrue(data.isAvailable());
        }

        // Request #3: expected last uploaded certificate
        Certificate[] chain3;
        try (RawHttpClient client = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse resp = client.get("https://localhost:" + port + "/index.html", credentials);
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
        DynamicCertificatesManager dynCertsMan = server.getDynamicCertificateManager();

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
                assertFalse(data.isAvailable()); // no certificate-data uploaded
                assertEquals(DynamicCertificateState.WAITING, dynCertsMan.getStateOfCertificate("localhost"));
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
            assertTrue(data.isAvailable());
            assertEquals(DynamicCertificateState.AVAILABLE, dynCertsMan.getStateOfCertificate("localhost"));

            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("https://localhost:" + port + "/index.html", credentials);
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
            assertTrue(data.isAvailable());
            assertEquals(DynamicCertificateState.AVAILABLE, dynCertsMan.getStateOfCertificate("localhost"));

            // check uploaded certificate
            try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
                RawHttpClient.HttpResponse r = c.get("https://localhost:" + port + "/index.html", credentials);
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
                assertFalse(data.isAvailable()); // no certificate-data uploaded
                assertEquals(DynamicCertificateState.WAITING, dynCertsMan.getStateOfCertificate("localhost"));
            }
        }
    }

    @Test
    public void testOCSP() throws Exception {
        configureAndStartServer();
        int port = server.getLocalPort();
        OcspStaplingManager ocspMan = mock(OcspStaplingManager.class);
        server.setOcspStaplingManager(ocspMan);
        DynamicCertificatesManager dynCertMan = server.getDynamicCertificateManager();

        // Upload certificate and check its OCSP response
        Certificate[] uploadedChain;
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        uploadedChain = generateSampleChain(endUserKeyPair, false);
        OCSPResp ocspResp = generateOCSPResponse(uploadedChain,CertificateStatus.GOOD);
        when(ocspMan.getOcspResponseForCertificate(uploadedChain[0])).thenReturn(ocspResp.getEncoded());
        byte[] chainData = createKeystore(uploadedChain, endUserKeyPair.getPrivate());
        try (RawHttpClient client = new RawHttpClient("localhost", DEFAULT_ADMIN_PORT)) {
            RawHttpClient.HttpResponse resp = uploadCertificate("localhost", null, chainData, client, credentials);
            assertTrue(resp.getBodyString().contains("SUCCESS"));
            CertificateData data = dynCertMan.getCertificateDataForDomain("localhost");
            assertNotNull(data);
            assertTrue(data.isManual());
            assertTrue(data.isAvailable());
        }
        // check ocsp response
        try (RawHttpClient c = new RawHttpClient("localhost", port, true, "localhost")) {
            RawHttpClient.HttpResponse r = c.get("https://localhost:" + port + "/index.html", credentials);
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
            BasicOCSPResp resp = basicBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").build(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE).getPrivate()),null, new Date());

            OCSPRespBuilder builder = new OCSPRespBuilder();
            return builder.build(OCSPRespBuilder.SUCCESSFUL, resp);
        } catch (Exception e) {
            throw new CertificateException("cannot generate OCSP response", e);
        }
    }

    private void configureAndStartServer() throws Exception {
        HttpUtils.overideJvmWideHttpsVerifier();

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("config.type", "database");
        config.put("db.jdbc.url", "jdbc:herddb:localhost");
        config.put("db.server.base.dir", tmpDir.newFolder().getAbsolutePath());
        startServer(config);

        // Default certificate
        String defaultCertificate = TestUtils.deployResource("localhost.p12", tmpDir.getRoot());
        config.put("certificate.1.hostname", "*");
        config.put("certificate.1.file", defaultCertificate);
        config.put("certificate.1.password", "testproxy");

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

}