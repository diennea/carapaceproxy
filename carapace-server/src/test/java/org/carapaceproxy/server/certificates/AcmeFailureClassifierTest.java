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
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Problem;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;
import org.shredzone.acme4j.exception.AcmeNetworkException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.exception.AcmeRateLimitedException;
import org.shredzone.acme4j.exception.AcmeServerException;
import org.shredzone.acme4j.toolbox.JSON;

class AcmeFailureClassifierTest {

    @Test
    void rejections() throws Exception {
        // the persisted state is unusable, retrying it won't help
        assertThat(AcmeFailureClassifier.isTransient(new AcmeException("unknown order"))).isFalse();
        assertThat(AcmeFailureClassifier.isTransient(new AcmeProtocolException("malformed CA response"))).isFalse();
        assertThat(AcmeFailureClassifier.isTransient(new AcmeLazyLoadingException(
                Order.class, URI.create("https://localhost/order").toURL(), new AcmeException("unknown order")))).isFalse();
        assertThat(AcmeFailureClassifier.isTransient(
                new AcmeServerException(problemOfType("urn:ietf:params:acme:error:unauthorized")))).isFalse();
    }

    @Test
    void transientFailures() throws Exception {
        // worth retrying in the same state
        assertThat(AcmeFailureClassifier.isTransient(new AcmeNetworkException(new IOException("io")))).isTrue();
        assertThat(AcmeFailureClassifier.isTransient(new AcmeRateLimitedException(
                problemOfType("urn:ietf:params:acme:error:rateLimited"), null, List.of()))).isTrue();
        assertThat(AcmeFailureClassifier.isTransient(
                new AcmeServerException(problemOfType("urn:ietf:params:acme:error:serverInternal")))).isTrue();
        assertThat(AcmeFailureClassifier.isTransient(
                new AcmeServerException(problemOfType("urn:ietf:params:acme:error:badNonce")))).isTrue();
        // a 5xx without a problem document, e.g., from a load balancer
        assertThat(AcmeFailureClassifier.isTransient(new AcmeException("HTTP 503"))).isTrue();
        // unwrapping a lazily-loaded resource failure preserves the transient classification
        assertThat(AcmeFailureClassifier.isTransient(new AcmeLazyLoadingException(
                Order.class,
                URI.create("https://localhost/order").toURL(),
                new AcmeNetworkException(new IOException("connection reset"))))).isTrue();
        // a message-less failure must not blow up the message match
        assertThat(AcmeFailureClassifier.isTransient(new AcmeException((String) null))).isFalse();
    }

    @Test
    void nonAcmeFailures() {
        // an unexpected local failure must be counted too, or it would retry invisibly forever
        assertThat(AcmeFailureClassifier.isTransient(new IllegalStateException("boom"))).isFalse();
    }

    private static Problem problemOfType(String type) throws Exception {
        return new Problem(JSON.parse("{\"type\": \"" + type + "\"}"), URI.create("https://localhost/order").toURL());
    }
}
