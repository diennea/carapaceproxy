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
package org.carapaceproxy.server.certificates.ocsp;

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesTestUtils.generateSampleChain;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import java.security.cert.Certificate;
import org.carapaceproxy.core.TrustStoreManager;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;

public class OcspStaplingManagerTest {

    @Test
    public void testStaplingSurvivesFailingCertificates() throws Exception {
        final var manager = spy(new OcspStaplingManager(mock(TrustStoreManager.class)));
        final Certificate[] first = generateSampleChain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), false);
        final Certificate[] second = generateSampleChain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), false);
        manager.addCertificateForStapling(first);
        manager.addCertificateForStapling(second);
        doThrow(new IllegalArgumentException("URI is not absolute")).when(manager).performStaplingForCertificate(any());

        manager.run();

        // whatever the iteration order, a failing certificate must not stop the other one
        verify(manager).performStaplingForCertificate(first);
        verify(manager).performStaplingForCertificate(second);
    }

    @Test
    public void testStaplingSurvivesError() throws Exception {
        final var manager = spy(new OcspStaplingManager(mock(TrustStoreManager.class)));
        final Certificate[] chain = generateSampleChain(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), false);
        manager.addCertificateForStapling(chain);
        doThrow(new AssertionError("unexpected")).when(manager).performStaplingForCertificate(chain);

        manager.run(); // an escaping error would cancel the scheduled task

        verify(manager).performStaplingForCertificate(chain);
    }
}
