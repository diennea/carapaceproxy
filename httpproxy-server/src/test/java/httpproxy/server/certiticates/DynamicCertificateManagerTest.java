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
package httpproxy.server.certiticates;

import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.AVAILABLE;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.ORDERING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.REQUEST_FAILED;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFIED;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFYING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.WAITING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.EXPIRED;
import httpproxy.server.certiticates.DynamicCertificateStore.DynamicCertificateStoreException;
import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_CERT_ALIAS;
import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_FORMAT;
import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_PW;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.utils.TestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import static org.shredzone.acme4j.Status.INVALID;
import static org.shredzone.acme4j.Status.VALID;
import org.shredzone.acme4j.challenge.Http01Challenge;

/**
 * Test del DynamicCertificateManager sia con casi reali sia con mock.
 *
 * @author paolo.venturi
 */
@RunWith(JUnitParamsRunner.class)
public class DynamicCertificateManagerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // Test reale del manager con 2 certificati da ordinare in simultanea (e 1 ignorato).
    @Ignore
    //@Test
    public void testRun() throws Exception {
        File basePath = folder.getRoot();
        Properties props = new Properties();

        String d2 = "site2-qapatch.informatica.it";
        String d6 = "site6-qapatch.informatica.it";

        props.setProperty("certificate.0.hostname", d2);
        props.setProperty("certificate.0.isdynamic", "true");

        props.setProperty("certificate.1.hostname", d6);
        props.setProperty("certificate.1.isdynamic", "true");

        props.setProperty("certificate.2.hostname", "diennea.com");
        props.setProperty("certificate.2.isdynamic", "false"); // skipped by manager

        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
        conf.configure(new PropertiesConfigurationStore(props));
        DynamicCertificateManager man = new DynamicCertificateManager(conf, basePath);

        TestUtils.waitForCondition(() -> {
            man.run();
            return man.getCertificateFile(d2) == null && man.getCertificateFile(d6) == null;
        }, 1);

        // Certificato 0
        File f = man.getCertificateFile(d2);
        assertNotNull(f);
        assertTrue(Files.exists(f.toPath()));
        try (InputStream is = new FileInputStream(f)) {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
            ks.load(is, KEYSTORE_PW);
            X509Certificate savedCert = (X509Certificate) ks.getCertificate(KEYSTORE_CERT_ALIAS);
            assertNotNull(savedCert);
            System.out.println(savedCert);
            assertTrue(savedCert.toString().contains("Subject: CN=site2-qapatch.informatica.it"));
            assertTrue(savedCert.toString().contains("accessMethod: caIssuers\n" + "   accessLocation: URIName: http://cert.stg-int-x1.letsencrypt.org/"));
        }

        // Certificato 1
        f = man.getCertificateFile(d6);
        assertNotNull(f);
        assertTrue(Files.exists(f.toPath()));
        try (InputStream is = new FileInputStream(f)) {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
            ks.load(is, KEYSTORE_PW);
            X509Certificate savedCert = (X509Certificate) ks.getCertificate(KEYSTORE_CERT_ALIAS);
            assertNotNull(savedCert);
            System.out.println(savedCert);

            assertTrue(savedCert.toString().contains("Subject: CN=site6-qapatch.informatica.it"));
            assertTrue(savedCert.toString().contains("accessMethod: caIssuers\n" + "   accessLocation: URIName: http://cert.stg-int-x1.letsencrypt.org/"));
        }

        assertNull(man.getCertificateFile("diennea.com"));
    }

    @Test
    @Parameters({
        "challenge_null",
        "challenge_status_invalid",
        "order_response_error",
        "storing_exception",
        "available_to_expired",
        "all_ok"
    })
    public void testCertificateStateManagement(String runCase) throws Exception {
        File basePath = folder.getRoot();

        Properties props = new Properties();
        String d = "mio-dominio.diennea.com";
        props.setProperty("certificate.0.hostname", d);
        props.setProperty("certificate.0.isdynamic", "true");

        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
        conf.configure(new PropertiesConfigurationStore(props));
        DynamicCertificateManager man = new DynamicCertificateManager(conf, basePath);

        ACMEClient ac = mock(ACMEClient.class);
        Order o = mock(Order.class);
        when(ac.createOrderForDomain(any())).thenReturn(o);
        Http01Challenge c = mock(Http01Challenge.class);
        when(ac.getHTTPChallengeForOrder(any())).thenReturn(runCase.equals("challenge_null") ? null : c);
        when(ac.checkResponseForChallenge(any())).thenReturn(runCase.equals("challenge_status_invalid") ? INVALID : VALID);
        when(ac.checkResponseForOrder(any())).thenReturn(runCase.equals("order_response_error") ? INVALID : VALID);
        Certificate cert = mock(Certificate.class);
        X509Certificate _cert = mock(X509Certificate.class);
        when(cert.getCertificateChain()).thenReturn(Arrays.asList(_cert));
        if (runCase.equals("available_to_expired")) {
            doThrow(CertificateExpiredException.class).when(_cert).checkValidity();
        }
        when(ac.fetchCertificateForOrder(any())).thenReturn(cert);

        DynamicCertificateStore s = mock(DynamicCertificateStore.class);
        if (runCase.equals("storing_exception")) {
            doThrow(DynamicCertificateStoreException.class).when(s).saveCertificate(any());
        }

        Field client = man.getClass().getDeclaredField("client");
        client.setAccessible(true);
        client.set(man, ac);
        Field store = man.getClass().getDeclaredField("store");
        store.setAccessible(true);
        store.set(man, s);

        // WAITING
        assertEquals(WAITING, man.getStateOfCertificate(d));
        man.run();
        assertEquals(runCase.equals("challenge_null") ? VERIFIED : VERIFYING, man.getStateOfCertificate(d));
        man.run();
        if (runCase.equals("challenge_null")) { // VERIFIED
            assertEquals(ORDERING, man.getStateOfCertificate(d));
        } else { // VERIFYING
            assertEquals(runCase.equals("challenge_status_invalid") ? REQUEST_FAILED : VERIFIED, man.getStateOfCertificate(d));
            man.run();
            assertEquals(runCase.equals("challenge_status_invalid") ? WAITING : ORDERING, man.getStateOfCertificate(d));
        }
        // ORDERING
        if (!runCase.equals("challenge_status_invalid")) {
            man.run();
            assertEquals(runCase.equals("order_response_error") || runCase.equals("storing_exception") ? REQUEST_FAILED : AVAILABLE, man.getStateOfCertificate(d));
            man.run();
            if (runCase.equals("order_response_error") || runCase.equals("storing_exception")) { // REQUEST_FAILED
                assertEquals(WAITING, man.getStateOfCertificate(d));
            } else { // AVAILABLE
                assertEquals(runCase.equals("available_to_expired") ? EXPIRED : AVAILABLE, man.getStateOfCertificate(d));
                man.run();
                assertEquals(runCase.equals("available_to_expired") ? WAITING : AVAILABLE, man.getStateOfCertificate(d));
            }
        }

    }
}
