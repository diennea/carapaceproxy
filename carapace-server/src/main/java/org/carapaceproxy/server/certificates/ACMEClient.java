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

import java.io.IOException;
import java.net.URL;
import java.security.KeyPair;
import java.security.Security;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.carapaceproxy.utils.CertificatesUtils;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Identifier;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * ACME client for automatic SSL certificate issuing.
 * <p>
 * It works against any RFC 8555 compliant CA, given its directory URL;
 * Let's Encrypt is the default one, and CAs requiring External Account Binding (e.g. DigiCert) are supported too.
 *
 * @author paolo.venturi
 *
 */
public class ACMEClient {

    private static final Logger LOG = LoggerFactory.getLogger(ACMEClient.class);

    // For production server use
    public static final String PRODUCTION_CA = "https://acme-v02.api.letsencrypt.org/directory"; //"acme://letsencrypt.org";

    // For testing server use
    public static final String TESTING_CA = "https://acme-staging-v02.api.letsencrypt.org/directory"; //"acme://letsencrypt.org/staging";

    private final KeyPair userKey;
    private final String directoryUrl;
    private final String kid;
    private final String hmac;

    /**
     * Build a client for the Let's Encrypt CA.
     *
     * @param userKey the ACME account key pair
     * @param testingMode whether to use the staging endpoint instead of the production one
     */
    public ACMEClient(KeyPair userKey, boolean testingMode) {
        this(userKey, testingMode ? TESTING_CA : PRODUCTION_CA, null, null);
    }

    /**
     * Build a client for any RFC 8555 compliant CA.
     *
     * @param userKey the ACME account key pair
     * @param directoryUrl the directory URL of the CA
     * @param kid the key identifier for External Account Binding, or null if the CA doesn't require it
     * @param hmac the base64url-encoded MAC key for External Account Binding, or null if the CA doesn't require it
     * @throws IllegalArgumentException if only one of {@code kid} and {@code hmac} is provided
     */
    public ACMEClient(KeyPair userKey, String directoryUrl, String kid, String hmac) {
        if ((kid == null || kid.isEmpty()) != (hmac == null || hmac.isEmpty())) {
            throw new IllegalArgumentException("kid and hmac must be provided together (external account binding)");
        }
        Security.addProvider(new BouncyCastleProvider());
        this.userKey = userKey;
        this.directoryUrl = directoryUrl;
        this.kid = kid;
        this.hmac = hmac;
    }

    /**
     * Finds your {@link Account} at the ACME server. It will be found by your user's public key.
     * If your key is not known to the server yet, a new account will be created.
     * <p>
     * This is a simple way of finding your {@link Account}. A better way is to get the URL of your
     * new account with {@link Account#getLocation()} and store it somewhere. If you need to get
     * access to your account later, reconnect to it via {@link Session#login(URL, KeyPair)}
     * by using the stored location.
     *
     * @return the {@link Login} bound to the account
     * @throws AcmeException if the account cannot be found or created
     */
    public Login getLogin() throws AcmeException {
        final var accountBuilder = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(userKey);
        if (kid != null && !kid.isEmpty()) {
            accountBuilder.withKeyIdentifier(kid, hmac);
        }
        return accountBuilder.createLogin(new Session(directoryUrl));
    }

    /*
     * Methods for step-by-step certificate issuing
     */
    public Order createOrderForDomain(Collection<String> domains) throws AcmeException {
        return getLogin().getAccount().newOrder()
                .domains(domains)
                .create();
    }

    public Map<String, Challenge> getChallengesForOrder(Order order) throws AcmeException {
        final var challenges = new HashMap<String, Challenge>();
        for (var auth: order.getAuthorizations()) {
            var domain = auth.getIdentifier().getDomain();
            if (auth.isWildcard() && !CertificatesUtils.isWildcard(domain)) { // LE removes wildcard notation
                domain = CertificatesUtils.addWildcard(domain);
            }
            LOG.debug("Authorization for domain {} status: {}", domain, auth.getStatus());
            // The authorization is already valid. No need to process a challenge.
            if (auth.getStatus() == Status.VALID) {
                continue;
            }

            LOG.debug("Retrieving challenge...");
            final var challenge = auth.isWildcard() ? dnsChallenge(auth) : httpChallenge(auth);
            if (challenge == null) {
                throw new AcmeException("No challenge found for domain " + domain);
            }

            // If the challenge is already verified, there's no need to execute it again.
            LOG.debug("Challenge for domain {} status:{}", domain, challenge.getStatus());
            if (challenge.getStatus() == Status.VALID) {
                continue;
            }

            challenges.put(domain, challenge);
        }
        return challenges;
    }

    /**
     * Prepares a HTTP challenge.
     * <p>
     * The verification of this challenge expects a file with a certain content to be reachable at a given path under the domain to be tested.
     * </p>
     *
     * @param auth {@link Authorization} to find the challenge in
     * @return {@link Http01Challenge} to verify
     */
    private Http01Challenge httpChallenge(Authorization auth) throws AcmeException {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                .orElseThrow(() -> new AcmeException("Found no " + Http01Challenge.TYPE + " challenge, don't know what to do..."));
        LOG.debug("It must be reachable at: http://{}/.well-known/acme-challenge/{}",
                auth.getIdentifier().getDomain(), challenge.getToken()
        );
        return challenge;
    }

    /**
     * Prepares a DNS challenge.
     * <p>
     * The verification of this challenge expects a TXT record with a certain content.
     * </p>
     *
     * @param auth {@link Authorization} to find the challenge in
     * @return {@link Challenge} to verify
     * @throws org.shredzone.acme4j.exception.AcmeException
     */
    public Challenge dnsChallenge(Authorization auth) throws AcmeException {
        Dns01Challenge challenge = auth
                .findChallenge(Dns01Challenge.class)
                .orElseThrow(() -> new AcmeException("Found no " + Dns01Challenge.TYPE + " challenge, don't know what to do..."));
        LOG.info("DNS-challenge _acme-challenge.{}. to save as TXT-record with content {}", auth.getIdentifier().getDomain(), challenge.getDigest());
        return challenge;
    }

    public Status checkResponseForChallenge(Challenge challenge) throws AcmeException {
        Status status = challenge.getStatus();
        if (status == Status.VALID) {
            // The authorization is already valid. No need to process a challenge.
            // or the challenge is already verified, there's no need to execute it again.
            return status;
        }
        if (status != Status.INVALID) {
            challenge.fetch();
        }
        return challenge.getStatus();
    }

    /**
     * Orders the certificate specified in the passed order with passed domain key.
     *
     * @param order
     * @param domainKeyPair
     * @throws IOException
     * @throws AcmeException
     */
    public void orderCertificate(Order order, KeyPair domainKeyPair) throws IOException, AcmeException {
        final var domains = order.getIdentifiers().stream()
                .map(Identifier::getDomain)
                .collect(Collectors.toList());
        LOG.info("Certificate ordering for domains {}", domains);
        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomains(domains);
        csrb.sign(domainKeyPair);
        order.execute(csrb.getEncoded());
    }

    public Status checkResponseForOrder(Order order) throws AcmeException {
        // fetch eagerly: on a freshly bound order any accessor would lazy-fetch anyway,
        // so this guarantees a single server round-trip per poll
        order.fetch();
        LOG.info("Certificate order checking for domain {}", order.getIdentifiers().getFirst().getDomain());
        Status status = order.getStatus();
        if (status == Status.VALID) {
            LOG.info("Order has been completed.");
        }
        return status;
    }

    public Certificate fetchCertificateForOrder(Order order) {
        // getCertificate() itself throws if the order is not ready
        Certificate certificate = order.getCertificate();
        LOG.info("Success! The certificate for domain {} has been generated!",
                order.getIdentifiers().getFirst().getDomain());
        LOG.info("Certificate URL: {}", certificate.getLocation());
        return certificate;
    }
}
