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
package org.carapaceproxy.configstore;

import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.net.URI;
import java.util.Set;
import org.junit.Test;

public class CertificateDataBindingEquivalentTest {

    private static final byte[] KEYSTORE_A = {0x01, 0x02, 0x03};
    private static final byte[] KEYSTORE_B = {0x04, 0x05, 0x06};
    private static final Set<String> SANS_A = Set.of("alt-a.example.com");
    private static final Set<String> SANS_B = Set.of("alt-b.example.com");

    @Test
    public void testBothNullReturnsTrue() {
        assertTrue(CertificateData.bindingEquivalent(null, null));
    }

    @Test
    public void testOneNullReturnsFalse() {
        final CertificateData cert = buildCert(KEYSTORE_A, SANS_A);
        assertFalse(CertificateData.bindingEquivalent(cert, null));
        assertFalse(CertificateData.bindingEquivalent(null, cert));
    }

    @Test
    public void testSameReferenceReturnsTrue() {
        final CertificateData cert = buildCert(KEYSTORE_A, SANS_A);
        assertTrue(CertificateData.bindingEquivalent(cert, cert));
    }

    @Test
    public void testIdenticalKeystoreAndSansReturnsTrueDespiteTransientState() throws Exception {
        // Staging symptom: a stuck ACME order ticks attemptsCount/message/state/pendingOrderLocation
        // every poll while keystore bytes and SANs stay the same.
        final CertificateData stable = buildCert(KEYSTORE_A, SANS_A);
        stable.setState(AVAILABLE);
        stable.setAttemptsCount(0);
        stable.setMessage("");
        stable.setPendingOrderLocation(null);

        final CertificateData ticked = buildCert(KEYSTORE_A.clone(), SANS_A);
        ticked.setState(REQUEST_FAILED);
        ticked.setAttemptsCount(11);
        ticked.setMessage("stuck order");
        ticked.setPendingOrderLocation(URI.create("https://acme/order/42").toURL());

        assertTrue(CertificateData.bindingEquivalent(stable, ticked));
    }

    @Test
    public void testKeystoreBytesDifferReturnsFalse() {
        final CertificateData a = buildCert(KEYSTORE_A, SANS_A);
        final CertificateData b = buildCert(KEYSTORE_B, SANS_A);
        assertFalse(CertificateData.bindingEquivalent(a, b));
    }

    @Test
    public void testKeystoreNullVsBytesReturnsFalse() {
        final CertificateData a = buildCert(null, SANS_A);
        final CertificateData b = buildCert(KEYSTORE_A, SANS_A);
        assertFalse(CertificateData.bindingEquivalent(a, b));
    }

    @Test
    public void testSubjectAltNamesDifferReturnsFalse() {
        final CertificateData a = buildCert(KEYSTORE_A, SANS_A);
        final CertificateData b = buildCert(KEYSTORE_A, SANS_B);
        assertFalse(CertificateData.bindingEquivalent(a, b));
    }

    private static CertificateData buildCert(final byte[] keystoreData, final Set<String> sans) {
        final CertificateData cert = new CertificateData("example.com", sans, null, WAITING, null, null);
        cert.setKeystoreData(keystoreData);
        return cert;
    }
}
