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
package httpproxy.server.certiticates;

import com.google.common.annotations.VisibleForTesting;
import httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.AVAILABLE;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.ORDERING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.REQUEST_FAILED;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFIED;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.VERIFYING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.WAITING;
import static httpproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.EXPIRED;
import httpproxy.server.certiticates.DynamicCertificateStore.DynamicCertificateStoreException;
import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;

/**
 *
 * Manager for SSL certificates issued via ACME and Let's Encrypt
 * 
 * @author paolo.venturi
 */
public final class DynamicCertificateManager implements Runnable {

    private static final Logger LOG = Logger.getLogger(DynamicCertificateManager.class.getName());
    private static final boolean TESTING_MODE = true;
    public static final String THREAD_NAME = "dynamic-certifica-manager";

    private Map<String, DynamicCertificate> certificates = new ConcurrentHashMap();
    private ACMEClient client; // Let's Encrypt client
    private long period; // in seconds    
    private ScheduledExecutorService scheduler;
    private DynamicCertificateStore store;

    public DynamicCertificateManager(RuntimeServerConfiguration initialConfiguration, File basePath) throws DynamicCertificateStoreException {
        loadConfiguration(initialConfiguration);
        this.store = new DynamicCertificateStore(basePath);
        this.client = new ACMEClient(this.store.loadOrCreateUserKey(), TESTING_MODE);
    }

    public void loadConfiguration(RuntimeServerConfiguration configuration) {
        loadCertificates(configuration.getCertificates());
        this.period = configuration.getDynamicCertificateManagerPeriod();
    }

    private void loadCertificates(Map<String, SSLCertificateConfiguration> certificates) {
        ConcurrentHashMap _certificates = new ConcurrentHashMap();
        for (Entry<String, SSLCertificateConfiguration> e : certificates.entrySet()) {
            SSLCertificateConfiguration config = e.getValue();
            if (config.isDynamic()) {
                _certificates.put(e.getKey(), this.certificates.containsKey(e.getKey())
                        ? this.certificates.get(e.getKey())
                        : new DynamicCertificate(config)
                );
            }
        }
        this.certificates = _certificates;
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
                String domain = cert.getHostname();
                DynamicCertificateState state = cert.getState();               
                switch (state) {
                    case WAITING: // certificato che deve essere generato/rinnovato
                        LOG.info("Certificate issuing process for domain: " + domain + " started.");
                        Order order = client.createOrderForDomain(domain);
                        cert.setPendingOrder(order);
                        Http01Challenge challenge = client.getHTTPChallengeForOrder(order);
                        if (challenge == null) {
                            cert.setState(VERIFIED);
                        } else {
                            executeChallengeForDomain(challenge, domain);
                            cert.setPendingChallenge(challenge);
                            cert.setState(VERIFYING);
                        }
                        break;

                    case VERIFYING: // richiesta verifica challenge a LE
                        Status status = client.checkResponseForChallenge(cert.getPendingChallenge());
                        if (status == Status.VALID) {
                            cert.setState(VERIFIED);
                        } else if (status == Status.INVALID) {
                            cert.setAvailable(false);
                            cert.setState(REQUEST_FAILED);
                        }
                        break;

                    case VERIFIED: // certificato verificato
                        KeyPair keys = this.store.loadOrCreateKeyForDomain(domain);
                        cert.setKeys(keys);
                        client.orderCertificate(cert.getPendingOrder(), keys);
                        cert.setState(ORDERING);
                        break;

                    case ORDERING: // ordine del certificato
                        Order _order = cert.getPendingOrder();
                        Status _status = client.checkResponseForOrder(_order);
                        if (_status == Status.VALID) {
                            cert.setChain(client.fetchCertificateForOrder(_order).getCertificateChain());
                            store.saveCertificate(cert);
                            cert.setAvailable(true);
                            cert.setState(AVAILABLE);
                            LOG.info("Certificate issuing for domain: " + domain + " succeed.");
                        } else if (_status == Status.INVALID) {
                            cert.setAvailable(false);
                            cert.setState(REQUEST_FAILED);
                        }
                        break;

                    case REQUEST_FAILED: // challenge/ordine falliti                        
                        LOG.info("Certificate issuing for domain: " + domain + " failed.");
                        cert.setState(WAITING);
                        break;

                    case AVAILABLE: // salvato/disponibile/non scaduto                        
                        if (cert.isExpired()) {
                            cert.setAvailable(false);
                            cert.setState(EXPIRED);
                        }
                        break;

                    case EXPIRED:     // certificato scaduto                        
                        LOG.info("Certificate for domain: " + domain + " exipired.");
                        cert.setState(WAITING);
                        break;
                        
                    default:
                        throw new IllegalStateException();
                }

            } catch (DynamicCertificateStoreException | IOException | AcmeException | NullPointerException ex) {
                LOG.log(Level.SEVERE, null, ex);
                cert.setAvailable(false);
                cert.setState(REQUEST_FAILED);
            }
        }
    }

    @VisibleForTesting
    public DynamicCertificateState getStateOfCertificate(String id) {
        DynamicCertificate c = certificates.get(id);
        if (c != null) {
            return c.getState();
        }
        return DynamicCertificateState.WAITING;
    }

    public File getCertificateFile(String id) {
        DynamicCertificate c = certificates.get(id);
        if (c != null && c.isAvailable()) {
            return c.getSslCertificateFile();
        }
        return null;
    }

    private void executeChallengeForDomain(Http01Challenge challenge, String domain) throws AcmeException {
//        String challengeRequiredFileName = challenge.getToken();
//        String challengeURL = String.format(
//                "http://{}/.well-known/acme-challenge/{}",
//                domain,
//                challengeRequiredFileName
//        );
//        String challengeFileContent = challenge.getAuthorization();

        //@todo routes setup for challenge response
        challenge.trigger();
    }
}
