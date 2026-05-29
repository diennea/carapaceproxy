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

import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.hasAnyBindingChange;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.configstore.CertificateData;
import org.junit.Test;

/**
 * Tests for the listener-reload gate added in {@link DynamicCertificatesManager#hasAnyBindingChange}.
 * Each test name describes the high-level behaviour driven by the predicate: when it returns
 * {@code false}, the reload skips {@code server.getListeners().reloadCurrentConfiguration()}.
 */
public class DynamicCertificatesManagerReloadGuardTest {

    private static final byte[] KEYSTORE_A = {0x10, 0x11, 0x12};
    private static final byte[] KEYSTORE_B = {0x20, 0x21, 0x22};

    @Test
    public void testReloadSkipsListenerWhenNoCertChanged() {
        final CertificateData stable = certWithKeystore("example.com", KEYSTORE_A);
        final Map<String, CertificateData> old = Map.of("example.com", stable);
        final Map<String, CertificateData> fresh = Map.of("example.com", certWithKeystore("example.com", KEYSTORE_A.clone()));
        assertFalse(hasAnyBindingChange(old, fresh));
    }

    @Test
    public void testReloadTriggersListenerOnKeystoreChange() {
        final Map<String, CertificateData> old = Map.of("example.com", certWithKeystore("example.com", KEYSTORE_A));
        final Map<String, CertificateData> fresh = Map.of("example.com", certWithKeystore("example.com", KEYSTORE_B));
        assertTrue(hasAnyBindingChange(old, fresh));
    }

    @Test
    public void testReloadTriggersListenerOnDomainAdded() {
        final Map<String, CertificateData> old = Map.of("example.com", certWithKeystore("example.com", KEYSTORE_A));
        final Map<String, CertificateData> fresh = Map.of(
                "example.com", certWithKeystore("example.com", KEYSTORE_A.clone()),
                "second.example.com", certWithKeystore("second.example.com", KEYSTORE_B));
        assertTrue(hasAnyBindingChange(old, fresh));
    }

    @Test
    public void testReloadTriggersListenerOnDomainRemoved() {
        final Map<String, CertificateData> old = Map.of(
                "example.com", certWithKeystore("example.com", KEYSTORE_A),
                "second.example.com", certWithKeystore("second.example.com", KEYSTORE_B));
        final Map<String, CertificateData> fresh = Map.of("example.com", certWithKeystore("example.com", KEYSTORE_A.clone()));
        assertTrue(hasAnyBindingChange(old, fresh));
    }

    @Test
    public void testReloadSkipsListenerWhenOnlyAttemptsCountIncremented() {
        // Exact staging symptom: a stuck ACME order ticks attemptsCount+message+state every poll
        // while keystoreData stays at its last successful value (or null on a brand-new domain).
        final CertificateData stuckPrev = certWithKeystore("stuck.example.com", null);
        stuckPrev.setState(REQUEST_FAILED);
        stuckPrev.setAttemptsCount(10);
        stuckPrev.setMessage("Challenge response verification failed, status is INVALID");

        final CertificateData stuckTicked = certWithKeystore("stuck.example.com", null);
        stuckTicked.setState(REQUEST_FAILED);
        stuckTicked.setAttemptsCount(11);
        stuckTicked.setMessage("Challenge response verification failed, status is INVALID");

        final Map<String, CertificateData> old = Map.of("stuck.example.com", stuckPrev);
        final Map<String, CertificateData> fresh = Map.of("stuck.example.com", stuckTicked);
        assertFalse(hasAnyBindingChange(old, fresh));
    }

    @Test
    public void testReloadTriggersListenerOnSanChange() {
        final CertificateData oldCert = certWithKeystoreAndSans("example.com", KEYSTORE_A, Set.of("alt.example.com"));
        final CertificateData freshCert = certWithKeystoreAndSans("example.com", KEYSTORE_A.clone(), Set.of("other.example.com"));
        final Map<String, CertificateData> old = Map.of("example.com", oldCert);
        final Map<String, CertificateData> fresh = Map.of("example.com", freshCert);
        assertTrue(hasAnyBindingChange(old, fresh));
    }

    private static CertificateData certWithKeystore(final String domain, final byte[] keystoreData) {
        return certWithKeystoreAndSans(domain, keystoreData, null);
    }

    private static CertificateData certWithKeystoreAndSans(final String domain, final byte[] keystoreData, final Set<String> sans) {
        final CertificateData cert = new CertificateData(domain, sans, null, AVAILABLE, null, null);
        cert.setKeystoreData(keystoreData);
        return cert;
    }
}
