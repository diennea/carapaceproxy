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

import static org.assertj.core.api.Assertions.assertThat;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.CertificatesUtils.compareChains;

import java.security.KeyPair;
import java.security.cert.Certificate;
import org.carapaceproxy.utils.CertificatesUtils;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.util.KeyPairUtils;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 *
 * @author paolo.venturi
 */
class CertificatesUtilsTest {

    @Test
    void compareCertificatesChains() throws Exception {
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        assertThat(compareChains(originalChain, originalChain)).isTrue();
        assertThat(compareChains(originalChain, null)).isFalse();
        assertThat(compareChains(originalChain, new Certificate[0])).isFalse();

        String encodedChain = base64EncodeCertificateChain(originalChain, endUserKeyPair.getPrivate());
        Certificate[] decodedChain = base64DecodeCertificateChain(encodedChain);
        assertThat(decodedChain)
                .isNotNull()
                .hasSameSizeAs(originalChain);
        for (int i = 0; i < decodedChain.length; i++) {
            Certificate decodedCert = decodedChain[i];
            assertThat(decodedCert).isNotNull();
            assertThat(decodedCert.getEncoded()).isEqualTo(originalChain[i].getEncoded());
        }
        assertThat(compareChains(originalChain, decodedChain)).isTrue();

        KeyPair endUserKeyPair2 = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] otherChain = generateSampleChain(endUserKeyPair2, false);
        assertThat(compareChains(originalChain, otherChain)).isFalse();
    }

    @Test
    void certificatesExpiration() throws Exception {
        {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, false); // not before == not after == today
            Date expiringDate = ((X509Certificate) chain[0]).getNotAfter();
            assertThat(CertificatesUtils.isCertificateExpired(expiringDate, 0)).isFalse();
            assertThat(CertificatesUtils.isCertificateExpired(expiringDate, 30)).isTrue(); // not after
        }
        {
            KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
            Certificate[] chain = generateSampleChain(endUserKeyPair, true); // not before == not after == today
            Date expiringDate = ((X509Certificate) chain[0]).getNotAfter();
            assertThat(CertificatesUtils.isCertificateExpired(expiringDate, 0)).isTrue();
            assertThat(CertificatesUtils.isCertificateExpired(expiringDate, 30)).isTrue(); // not after
        }
    }
}
