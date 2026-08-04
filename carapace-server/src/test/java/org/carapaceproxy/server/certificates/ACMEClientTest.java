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

import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.Test;
import org.shredzone.acme4j.Identifier;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;

public class ACMEClientTest {

    private final ACMEClient client = new ACMEClient(KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE), true);

    @Test
    public void testCheckResponseForChallengeReturnsRefreshedStatus() throws Exception {
        Challenge challenge = mock(Challenge.class);
        when(challenge.getStatus()).thenReturn(Status.PROCESSING, Status.VALID);

        assertEquals(Status.VALID, client.checkResponseForChallenge(challenge));
        verify(challenge).fetch();
    }

    @Test
    public void testCheckResponseForChallengeSkipsFetchWhenAlreadyValid() throws Exception {
        Challenge challenge = mock(Challenge.class);
        when(challenge.getStatus()).thenReturn(Status.VALID);

        assertEquals(Status.VALID, client.checkResponseForChallenge(challenge));
        verify(challenge, never()).fetch();
    }

    @Test
    public void testCheckResponseForChallengeSkipsFetchWhenInvalid() throws Exception {
        Challenge challenge = mock(Challenge.class);
        when(challenge.getStatus()).thenReturn(Status.INVALID);

        assertEquals(Status.INVALID, client.checkResponseForChallenge(challenge));
        verify(challenge, never()).fetch();
    }

    @Test
    public void testCheckResponseForOrderReturnsFetchedStatus() throws Exception {
        Order order = mockOrder();
        when(order.getStatus()).thenReturn(Status.VALID);

        assertEquals(Status.VALID, client.checkResponseForOrder(order));
        verify(order, times(1)).fetch();
    }

    @Test
    public void testCheckResponseForOrderFetchesOncePerPoll() throws Exception {
        // a freshly bound order has no cached state and any accessor would lazy-fetch,
        // so the explicit eager fetch has to be the only server round-trip of the poll
        Order order = mockOrder();
        when(order.getStatus()).thenReturn(Status.PROCESSING);

        assertEquals(Status.PROCESSING, client.checkResponseForOrder(order));
        verify(order, times(1)).fetch();
    }

    private static Order mockOrder() {
        Order order = mock(Order.class);
        when(order.getIdentifiers()).thenReturn(List.of(Identifier.dns("example.com")));
        return order;
    }
}
