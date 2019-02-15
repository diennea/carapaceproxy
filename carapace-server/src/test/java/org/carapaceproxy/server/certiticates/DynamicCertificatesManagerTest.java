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
package org.carapaceproxy.server.certiticates;

import java.lang.reflect.Field;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.ConfigurationStoreException;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.EXPIRED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.ORDERING;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFIED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFYING;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.WAITING;
import static org.junit.Assert.*;
import org.junit.Test;
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
 * Test for DynamicCertificatesManager.
 *
 * @author paolo.venturi
 */
@RunWith(JUnitParamsRunner.class)
public class DynamicCertificatesManagerTest {

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
        // ACME mocking
        ACMEClient ac = mock(ACMEClient.class);
        Order o = mock(Order.class);
        when(ac.createOrderForDomain(any())).thenReturn(o);
        Http01Challenge c = mock(Http01Challenge.class);
        when(c.getToken()).thenReturn("");
        when(c.getAuthorization()).thenReturn("");
        when(ac.getHTTPChallengeForOrder(any())).thenReturn(runCase.equals("challenge_null") ? null : c);
        when(ac.checkResponseForChallenge(any())).thenReturn(runCase.equals("challenge_status_invalid") ? INVALID : VALID);
        when(ac.checkResponseForOrder(any())).thenReturn(runCase.equals("order_response_error") ? INVALID : VALID);
        Certificate cert = mock(Certificate.class);
        X509Certificate _cert = mock(X509Certificate.class);
        when(cert.getCertificateChain()).thenReturn(Arrays.asList(_cert));
        when(_cert.getEncoded()).thenReturn(new byte[0]);
        if (runCase.equals("available_to_expired")) {
            doThrow(CertificateExpiredException.class).when(_cert).checkValidity();
        }
        when(ac.fetchCertificateForOrder(any())).thenReturn(cert);

        DynamicCertificatesManager man = new DynamicCertificatesManager();
        Field client = man.getClass().getDeclaredField("client");
        client.setAccessible(true);
        client.set(man, ac);

        // Store mocking
        ConfigurationStore s = mock(ConfigurationStore.class);
        if (runCase.equals("storing_exception")) {
            doThrow(ConfigurationStoreException.class).when(s).saveCertificate(any());
        }
        man.setConfigurationStore(s);

        // Manager setup
        Properties props = new Properties();
        String d = "localhost";
        props.setProperty("certificate.0.hostname", d);
        props.setProperty("certificate.0.dynamic", "true");
        ConfigurationStore configStore = new PropertiesConfigurationStore(props);
        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
        conf.configure(configStore);
        man.reloadConfiguration(conf);

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
