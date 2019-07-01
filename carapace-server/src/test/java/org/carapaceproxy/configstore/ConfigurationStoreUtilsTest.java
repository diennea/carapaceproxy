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
package org.carapaceproxy.configstore;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePrivateKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePublicKey;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeKey;
import static org.carapaceproxy.server.certiticates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.carapaceproxy.utils.TestUtils.assertEqualsKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 *
 * @author paolo.venturi
 */
public class ConfigurationStoreUtilsTest {

    @Test
    public void testBase64EncodeDecodeKeys() throws Exception {
        KeyPair pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();

        String _privateKey = base64EncodeKey(privateKey);
        System.out.println("Size of privateKey: " + _privateKey.length());
        String _publicKey = base64EncodeKey(publicKey);
        System.out.println("Size of publicKey: " + _publicKey.length());

        assertEqualsKey(privateKey, base64DecodePrivateKey(_privateKey));
        assertEqualsKey(publicKey, base64DecodePublicKey(_publicKey));
    }

    @Test
    public void testBase64EncodeEncodeCertificateChain() throws Exception {
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        String encodedChain = base64EncodeCertificateChain(originalChain, endUserKeyPair.getPrivate());
        Certificate[] decodedChain = base64DecodeCertificateChain(encodedChain);

        assertNotNull(decodedChain);
        assertEquals(originalChain.length, decodedChain.length);
        for (int i = 0; i < decodedChain.length; i++) {
            Certificate decodedCert = decodedChain[i];
            assertNotNull(decodedCert);
            assertTrue(Arrays.equals(decodedCert.getEncoded(), originalChain[i].getEncoded()));
        }
    }

}
