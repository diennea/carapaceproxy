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

import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.Arrays;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;
import static org.carapaceproxy.utils.CertificatesUtils.compareChains;
import org.carapaceproxy.utils.CertificatesUtils;

/**
 *
 * @author paolo.venturi
 */
public class CertificatesUtilsTest {

    @Test
    public void testCompareCertificatesChains() throws Exception {
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        assertTrue(compareChains(originalChain, originalChain));
        assertFalse(compareChains(originalChain, null));
        assertFalse(compareChains(originalChain, new Certificate[0]));

        String encodedChain = base64EncodeCertificateChain(originalChain, endUserKeyPair.getPrivate());
        Certificate[] decodedChain = base64DecodeCertificateChain(encodedChain);
        assertNotNull(decodedChain);
        assertEquals(originalChain.length, decodedChain.length);
        for (int i = 0; i < decodedChain.length; i++) {
            Certificate decodedCert = decodedChain[i];
            assertNotNull(decodedCert);
            assertTrue(Arrays.equals(decodedCert.getEncoded(), originalChain[i].getEncoded()));
        }
        assertTrue(compareChains(originalChain, decodedChain));

        KeyPair endUserKeyPair2 = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] otherChain = generateSampleChain(endUserKeyPair2, false);
        assertFalse(compareChains(originalChain, otherChain));
    }

    @Test
    public void testCertificatesExpiration() throws Exception {
        {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, false); // not before == not after == today
            assertFalse(CertificatesUtils.isCertificateExpired(chain, 0));
            assertTrue(CertificatesUtils.isCertificateExpired(chain, -30)); // not before
            assertTrue(CertificatesUtils.isCertificateExpired(chain, 30)); // not after
        }
        {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, true); // not before == not after == today
            assertTrue(CertificatesUtils.isCertificateExpired(chain, 0));
            assertTrue(CertificatesUtils.isCertificateExpired(chain, -30)); // not before
            assertTrue(CertificatesUtils.isCertificateExpired(chain, 30)); // not after
        }
    }
}
