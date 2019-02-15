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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.ConfigurationStoreException;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.EXPIRED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.ORDERING;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFIED;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFYING;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.WAITING;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;

/**
 *
 * Manager for SSL certificates issued via ACME and Let's Encrypt
 *
 * @author paolo.venturi
 */
public class DynamicCertificatesManager implements Runnable {

    public static final String THREAD_NAME = "dynamic-certificates-manager";

    // RSA key size of generated key pairs
    public static final int DEFAULT_KEYPAIRS_SIZE = 2048;
    private static final Logger LOG = Logger.getLogger(DynamicCertificatesManager.class.getName());
    private static final boolean TESTING_MODE = Boolean.getBoolean("carapace.acme.testmode");

    private Map<String, DynamicCertificate> certificates = new ConcurrentHashMap();
    private Map<String, String> challengesTokens = new ConcurrentHashMap();
    private ACMEClient client; // Let's Encrypt client
    private ScheduledExecutorService scheduler;
    private ConfigurationStore store;
    private int period = 0; // in seconds
    private int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;

    public void setConfigurationStore(ConfigurationStore configStore) {
        this.store = configStore;
    }

    public void reloadConfiguration(RuntimeServerConfiguration configuration) {
        if (store == null) {
            throw new DynamicCertificatesManagerException("ConfigurationStore not set.");
        }
        if (client == null) {
            client = new ACMEClient(loadOrCreateAcmeUserKeyPair(), TESTING_MODE);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        period = configuration.getDynamicCertificateManagerPeriod();
        keyPairsSize = configuration.getKeyPairsSize();
        loadCertificates(configuration.getCertificates());
        if (scheduler != null) {
            start();
        }
    }

    private KeyPair loadOrCreateAcmeUserKeyPair() {
        KeyPair pair = store.loadAcmeUserKeyPair();
        if (pair == null) {
            pair = KeyPairUtils.createKeyPair(keyPairsSize > 0 ? keyPairsSize : DEFAULT_KEYPAIRS_SIZE);
            store.saveAcmeUserKey(pair);
        }

        return pair;
    }

    private void loadCertificates(Map<String, SSLCertificateConfiguration> certificates) {
        try {
            ConcurrentHashMap _certificates = new ConcurrentHashMap();
            for (Entry<String, SSLCertificateConfiguration> e : certificates.entrySet()) {
                SSLCertificateConfiguration config = e.getValue();
                if (config.isDynamic()) {
                    String domain = config.getHostname();
                    DynamicCertificate dc;
                    if (this.certificates.containsKey(domain)) { // Service up: new configuration with yet managed domain loaded
                        dc = this.certificates.get(domain);
                    } else {
                        // Service restarted: database lookup for existing managed certificate
                        CertificateData data = store.loadCertificateForDomain(domain);
                        if (data != null) {
                            dc = new DynamicCertificate(data);
                        } else { // New domain to manage
                            dc = new DynamicCertificate(domain);
                        }
                    }
                    _certificates.put(domain, dc);
                }
            }
            this.certificates = _certificates;
        } catch (GeneralSecurityException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates configuration.", e);
        }

    }

    public void start() {
        if (period > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(THREAD_NAME).build());
            scheduler.scheduleWithFixedDelay(this, 0, period, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public void run() {
        for (DynamicCertificate cert : certificates.values()) {
            try {
                String domain = cert.getDomain();
                DynamicCertificateState state = cert.getState();
                switch (state) {
                    case WAITING: // certificate waiting to be issues/renew
                        LOG.info("Certificate issuing process for domain: " + domain + " started.");
                        Order order = client.createOrderForDomain(domain);
                        cert.setPendingOrder(order);
                        Http01Challenge challenge = client.getHTTPChallengeForOrder(order);
                        if (challenge == null) {
                            cert.setState(VERIFIED);
                        } else {
                            triggerChallenge(challenge);
                            cert.setPendingChallenge(challenge);
                            cert.setState(VERIFYING);
                        }
                        break;

                    case VERIFYING: // challenge verification by LE pending
                        Challenge pendingChallenge = cert.getPendingChallenge();
                        Status status = client.checkResponseForChallenge(pendingChallenge);
                        if (status == Status.VALID) {
                            cert.setState(VERIFIED);
                            challengesTokens.remove(((Http01Challenge) pendingChallenge).getToken());
                        } else if (status == Status.INVALID) {
                            cert.setAvailable(false);
                            cert.setState(REQUEST_FAILED);
                        }
                        break;

                    case VERIFIED: // challenge succeded
                        KeyPair keys = cert.getKeyPair(); // service up and certificate to renew
                        if (keys == null) {
                            keys = loadOrCreateKeyPairForDomain(domain); // new certificate or service restarted: key generation v/s load from db
                        }
                        cert.setKeyPair(keys);
                        client.orderCertificate(cert.getPendingOrder(), keys);
                        cert.setState(ORDERING);
                        break;

                    case ORDERING: // certificate ordering
                        Order _order = cert.getPendingOrder();
                        Status _status = client.checkResponseForOrder(_order);
                        if (_status == Status.VALID) {
                            cert.setChain(client.fetchCertificateForOrder(_order).getCertificateChain());
                            cert.setAvailable(true);
                            cert.setState(AVAILABLE);
                            CertificateData data = cert.getData();
                            store.saveCertificate(data);
                            LOG.info("Certificate issuing for domain: " + domain + " succeed.");
                        } else if (_status == Status.INVALID) {
                            cert.setAvailable(false);
                            cert.setState(REQUEST_FAILED);
                        }
                        break;

                    case REQUEST_FAILED: // challenge/order failed
                        LOG.info("Certificate issuing for domain: " + domain + " failed.");
                        cert.setState(WAITING);
                        break;

                    case AVAILABLE: // certificate saved/available/not expired
                        if (cert.isExpired()) {
                            cert.setAvailable(false);
                            cert.setState(EXPIRED);
                            CertificateData data = cert.getData();
                            store.saveCertificate(data);
                        }
                        break;

                    case EXPIRED:     // certificate expired
                        LOG.info("Certificate for domain: " + domain + " exipired.");
                        cert.setState(WAITING);
                        break;

                    default:
                        throw new IllegalStateException();
                }

            } catch (AcmeException | IOException | GeneralSecurityException | ConfigurationStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
                cert.setAvailable(false);
                cert.setState(REQUEST_FAILED);
            }
        }
    }

    private void triggerChallenge(Http01Challenge challenge) throws AcmeException {
        challengesTokens.put(challenge.getToken(), challenge.getAuthorization());
        challenge.trigger();
    }

    public String getChallengeToken(String tokenName) {
        return challengesTokens.get(tokenName);
    }

    private KeyPair loadOrCreateKeyPairForDomain(String domain) {
        KeyPair pair = store.loadKeyPairForDomain(domain);
        if (pair == null) {
            pair = KeyPairUtils.createKeyPair(keyPairsSize > 0 ? keyPairsSize : DEFAULT_KEYPAIRS_SIZE);
            store.saveKeyPairForDomain(pair, domain);
        }

        return pair;
    }

    @VisibleForTesting
    public DynamicCertificateState getStateOfCertificate(String id) {
        DynamicCertificate c = certificates.get(id);
        if (c != null) {
            return c.getState();
        }
        return DynamicCertificateState.WAITING;
    }

    /**
     *
     * @param domain
     * @return PKCS12 Keystore content
     */
    public byte[] getCertificateForDomain(String domain) {
        DynamicCertificate c = certificates.get(domain);
        if (c == null) {
            LOG.log(Level.SEVERE, "No dynamic certificate for domain {0}", domain);
            return null;
        }
        if (!c.isAvailable()) {
            LOG.log(Level.SEVERE, "Dynamic certificate for domain {0} is not available yet: {1}", new Object[]{domain, c});
            return null;
        }

        CertificateData data = store.loadCertificateForDomain(domain);
        if (data == null) {
            LOG.log(Level.SEVERE, "Dynamic certificate for domain {0} is not available but without data: {1}", new Object[]{domain, c});
            return null;
        }
        return Base64.getDecoder().decode(data.getChain());
    }

}
