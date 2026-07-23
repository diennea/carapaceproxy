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
package org.carapaceproxy.server.certificates;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;
import org.shredzone.acme4j.exception.AcmeNetworkException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.exception.AcmeRateLimitedException;
import org.shredzone.acme4j.exception.AcmeServerException;

/**
 * Classification of the failures raised while talking to an ACME CA.
 */
final class AcmeFailureClassifier {

    private static final Set<URI> TRANSIENT_ACME_PROBLEMS = Set.of(
            URI.create("urn:ietf:params:acme:error:serverInternal"), // CA outage
            URI.create("urn:ietf:params:acme:error:badNonce") // escaped acme4j's own retry loop
    );
    // matches the bare AcmeException("HTTP 503") thrown by acme4j (as of 5.1.0) on 5xx without a problem document
    private static final Pattern HTTP_SERVER_ERROR = Pattern.compile("\\bHTTP 5\\d\\d\\b");

    private AcmeFailureClassifier() {
    }

    /**
     * Whether the CA actively rejected the request,
     * as opposed to a transient failure (network error, rate limiting, CA outage) worth retrying in the same state.
     * <p>
     * A rejection means the persisted certificate state is no longer usable,
     * e.g. a pending order belonging to a different CA after a provider change,
     * so the failure has to be {@link CertificateData#error(String) counted on the certificate}
     * for it to fall back to a fresh order.
     * acme4j may defer the server round-trip of bound resources, wrapping failures in {@link AcmeLazyLoadingException}.
     *
     * @param ex the failure raised while processing a certificate
     * @return true if the CA rejected the request
     */
    static boolean isRejectedByProvider(Exception ex) {
        final var cause = ex instanceof AcmeLazyLoadingException lazy ? lazy.getCause() : ex;
        return switch (cause) {
            case AcmeNetworkException ignored -> false;
            case AcmeRateLimitedException ignored -> false;
            case AcmeServerException server -> !TRANSIENT_ACME_PROBLEMS.contains(server.getType());
            // a 5xx without a problem document (e.g., a CA outage) surfaces as a bare exception;
            // an unrecognizable message counts as rejection: worst case is a fresh order instead of being stuck forever
            case AcmeException e -> e.getMessage() == null || !HTTP_SERVER_ERROR.matcher(e.getMessage()).find();
            case AcmeProtocolException ignored -> true; // malformed CA response, retrying it won't help
            default -> false;
        };
    }
}
