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
package org.carapaceproxy.server.certiticates;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * A simple Let's Encrypt ACME client for automatic ssl certificate issuing.
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

    private boolean testingModeOn;
    private final KeyPair userKey;

    public enum ChallengeType {
        HTTP
    }

    public ACMEClient(KeyPair userKey, boolean testingMode) {
        Security.addProvider(new BouncyCastleProvider());
        this.userKey = userKey;
        this.testingModeOn = testingMode;
    }

    /**
     * Finds your {@link Account} at the ACME server.It will be found by your user's public key.If your key is not known
     * to the server yet, a new account will be created.<p>
     * This is a simple way of finding your {@link Account}. A better way is to get the URL and KeyIdentifier of your
     * new account with {@link Account#getLocation()}
     * {@link Session#getKeyIdentifier()} and store it somewhere. If you need to get access to your account later,
     * reconnect to it via {@link Account#bind(Session, URI)} by using the stored location.
     *
     * @return
     * @throws java.io.IOException
     * @throws org.shredzone.acme4j.exception.AcmeException
     */
    public Login getLogin() throws IOException, AcmeException {
        Session session = new Session(testingModeOn ? TESTING_CA : PRODUCTION_CA);

        return new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(userKey)
                .createLogin(session);
    }

    /*
     * Methods for step-by-step certificate issuing
     */
    public Order createOrderForDomain(String domains) throws AcmeException, IOException {
        // Get the Account.
        // If there is no account yet, create a new one.
        Account acct = getLogin().getAccount();

        // Order the certificate
        return acct.newOrder().domains(domains).create();
    }

    public Http01Challenge getHTTPChallengeForOrder(Order order) throws AcmeException {
        Authorization auth = order.getAuthorizations().get(0);
        String domain = auth.getIdentifier().getDomain();
        LOG.info("Authorization for domain {}", domain);

        // The authorization is already valid. No need to process a challenge.
        if (auth.getStatus() == Status.VALID) {
            return null;
        }

        Http01Challenge challenge = httpChallenge(auth);

        if (challenge == null) {
            throw new AcmeException("No challenge found");
        }

        // If the challenge is already verified, there's no need to execute it again.
        if (challenge.getStatus() == Status.VALID) {
            return null;
        }

        return challenge;
    }

    /**
     * Prepares a HTTP challenge.
     * <p>
     * The verification of this challenge expects a file with a certain content to be reachable at a given path under
     * the domain to be tested.
     * <p>
     * This example outputs instructions that need to be executed manually. In a production environment, you would
     * rather generate this file automatically, or maybe use a servlet that returns
     * {@link Http01Challenge#getAuthorization()}.
     *
     * @param auth {@link Authorization} to find the challenge in
     * @return {@link Http01Challenge} to verify
     */
    private Http01Challenge httpChallenge(Authorization auth) throws AcmeException {
        // Find a single http-01 challenge
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);
        if (challenge == null) {
            throw new AcmeException("Found no " + Http01Challenge.TYPE + " challenge, don't know what to do...");
        }

        // Output the challenge, wait for acknowledge...
        LOG.info("It must be reachable at: http://{}/.well-known/acme-challenge/{}",
                auth.getIdentifier().getDomain(), challenge.getToken());

        return challenge;
    }

    public Status checkResponseForChallenge(Challenge challenge) throws AcmeException, IOException {
        Status status = challenge.getStatus();

        // The authorization is already valid. No need to process a challenge.
        // or the challenge is already verified, there's no need to execute it again.
        if (status == Status.VALID) {

        } else if (status != Status.INVALID) {
            challenge.update();
        }

        return status;
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
        String domain = order.getIdentifiers().get(0).getDomain();

        // Generate a CSR (Certificate signing request) for all of the domains, and sign it with the domain key pair.
        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomain(domain);
        //csrb.setOrganization("The Example Organization")
        csrb.sign(domainKeyPair);

        // Write the CSR to a file, for later use (p.e. renewal of the certificate)                     
//        File file = new File(basePath, domain + CSR_FILE);
//        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
//            csrb.write(out);
//        }
        // Order the certificate
        LOG.info("Certificate ordering for domain {}", domain);
        order.execute(csrb.getEncoded());
    }

    public Status checkResponseForOrder(Order order) throws IOException, AcmeException {
        LOG.info("Certificate order checking for domain {}", order.getIdentifiers().get(0).getDomain());
        Status status = order.getStatus();

        if (status == Status.VALID) {
            LOG.info("Order has been completed.");
        } else if (status != Status.INVALID) {
            order.update();
        }

        return status;
    }

    public Certificate fetchCertificateForOrder(Order order) throws AcmeException, IOException {
        // Get the certificate        
        Certificate certificate = order.getCertificate();
        String domain = order.getIdentifiers().get(0).getDomain();
        if (certificate == null) {
            throw new AcmeException("Certificate not fetched");
        }
        LOG.info("Success! The certificate for domain {} has been generated!", domain);
        LOG.info("Certificate URL: {}", certificate.getLocation());

        return certificate;
    }
}
