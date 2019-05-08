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
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.cluster.GroupMembershipHandler;
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
    private static final String EVENT_CERT_AVAIL_CHANGED = "certAvailChanged";

    private Map<String, DynamicCertificate> certificates = new ConcurrentHashMap();
    private ACMEClient client; // Let's Encrypt client

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean started; // keep track of start() calling

    private ConfigurationStore store;
    private volatile int period = 0; // in seconds
    private volatile int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;
    private GroupMembershipHandler groupMembershipHandler;

    public void setConfigurationStore(ConfigurationStore configStore) {
        this.store = configStore;
    }

    public void setGroupMembershipHandler(GroupMembershipHandler groupMembershipHandler) {
        this.groupMembershipHandler = groupMembershipHandler;
        groupMembershipHandler.watchEvent(EVENT_CERT_AVAIL_CHANGED, new CertAvailChangeCallback());
    }

    @VisibleForTesting
    public int getPeriod() {
        return period;
    }

    @VisibleForTesting
    public void setPeriod(int period) {
        this.period = period;
    }

    public synchronized void reloadConfiguration(RuntimeServerConfiguration configuration) {
        if (store == null) {
            throw new DynamicCertificatesManagerException("ConfigurationStore not set.");
        }
        keyPairsSize = configuration.getKeyPairsSize();
        if (client == null) {
            client = new ACMEClient(loadOrCreateAcmeUserKeyPair(), TESTING_MODE);
        }
        loadCertificates(configuration.getCertificates());
        period = configuration.getDynamicCertificatesManagerPeriod();
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        if (scheduledFuture != null || started) {
            start();
        }
    }

    private KeyPair loadOrCreateAcmeUserKeyPair() {
        KeyPair pair = store.loadAcmeUserKeyPair();
        if (pair == null) {
            pair = KeyPairUtils.createKeyPair(keyPairsSize > 0 ? keyPairsSize : DEFAULT_KEYPAIRS_SIZE);
            if (!store.saveAcmeUserKey(pair)) {
                pair = store.loadAcmeUserKeyPair(); // load key created concurrently by another peer
            }
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
                    _certificates.put(domain, loadOrCreateDynamicCertificateForDomain(domain));
                }
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates configuration.", e);
        }
    }

    private DynamicCertificate loadOrCreateDynamicCertificateForDomain(String domain) throws GeneralSecurityException, MalformedURLException {
        DynamicCertificate dc;
        CertificateData data = store.loadCertificateForDomain(domain);
        if (data != null) {
            dc = new DynamicCertificate(data);
        } else { // New domain to manage
            dc = new DynamicCertificate(domain);
        }
        return dc;
    }   

    public synchronized void start() {
        started = true;
        if (period <= 0) {
            return;
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(THREAD_NAME).build());
        }

        LOG.info("Starting DynamicCertificatesManager, period: " + period + " seconds" + (TESTING_MODE ? " (TESTING_MODE)" : ""));
        scheduledFuture = scheduler.scheduleWithFixedDelay(this, 0, period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        started = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
                scheduler = null;
                scheduledFuture = null;
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            groupMembershipHandler.executeInMutex(THREAD_NAME, () -> {
                // Only one node of the cluser execute this (until death)
                certificatesLifecycle();
            });
        } catch (Exception e) {
            LOG.log(Level.SEVERE, null, e);
        }
    }

    private void certificatesLifecycle() {
        boolean updateDB = true;
        boolean notifyCertAvailChanged = false;
        for (String domain : certificates.keySet()) {
            try {
                DynamicCertificate cert = new DynamicCertificate(store.loadCertificateForDomain(domain));
                switch (cert.getState()) {
                    case WAITING: // certificate waiting to be issues/renew
                        LOG.info("Certificate issuing process for domain: " + domain + " started.");
                        Order order = client.createOrderForDomain(domain);
                        cert.setPendingOrder(order.getLocation());
                        Http01Challenge challenge = client.getHTTPChallengeForOrder(order);
                        if (challenge == null) {
                            cert.setState(VERIFIED);
                        } else {
                            triggerChallenge(challenge);
                            cert.setPendingChallenge(challenge.getJSON());
                            cert.setState(VERIFYING);
                        }
                        break;

                    case VERIFYING: // challenge verification by LE pending
                        Http01Challenge pendingChallenge = new Http01Challenge(client.getLogin(), cert.getPendingChallenge());
                        Status status = client.checkResponseForChallenge(pendingChallenge);
                        if (status == Status.VALID) {
                            cert.setState(VERIFIED);
                            store.deleteAcmeChallengeToken(pendingChallenge.getToken());
                        } else if (status == Status.INVALID) {
                            cert.setState(REQUEST_FAILED);
                        }
                        break;

                    case VERIFIED: // challenge succeded
                        Order pendingOrder = client.getLogin().bindOrder(cert.getPendingOrder());
                        KeyPair keys = loadOrCreateKeyPairForDomain(domain);
                        client.orderCertificate(pendingOrder, keys);
                        cert.setState(ORDERING);
                        break;

                    case ORDERING: // certificate ordering
                        Order _order = client.getLogin().bindOrder(cert.getPendingOrder());
                        Status _status = client.checkResponseForOrder(_order);
                        if (_status == Status.VALID) {
                            cert.setChain(client.fetchCertificateForOrder(_order).getCertificateChain());
                            cert.setAvailable(true);
                            cert.setState(AVAILABLE);
                            notifyCertAvailChanged = true; // all other peers need to know that this cert is available.
                            LOG.info("Certificate issuing for domain: " + domain + " succeed.");
                        } else if (_status == Status.INVALID) {
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
                            notifyCertAvailChanged = true; // all other peers need to know that this cert is expired.
                        } else {
                            updateDB = false;
                        }
                        break;

                    case EXPIRED:     // certificate expired
                        LOG.info("Certificate for domain: " + domain + " exipired.");
                        cert.setState(WAITING);
                        break;

                    default:
                        throw new IllegalStateException();
                }
                if (updateDB) {
                    store.saveCertificate(cert.getData());
                }
                if (notifyCertAvailChanged) {
                    groupMembershipHandler.fireEvent(EVENT_CERT_AVAIL_CHANGED);
                }
            } catch (AcmeException | IOException | ConfigurationStoreException | GeneralSecurityException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }
    }

    private void triggerChallenge(Http01Challenge challenge) throws AcmeException {
        store.saveAcmeChallengeToken(challenge.getToken(), challenge.getAuthorization());
        challenge.trigger();
    }

    public String getChallengeToken(String tokenName) {
        return store.loadAcmeChallengeToken(tokenName);
    }

    private KeyPair loadOrCreateKeyPairForDomain(String domain) {
        KeyPair pair = store.loadKeyPairForDomain(domain);
        if (pair == null) {
            pair = KeyPairUtils.createKeyPair(keyPairsSize > 0 ? keyPairsSize : DEFAULT_KEYPAIRS_SIZE);
            if (!store.saveKeyPairForDomain(pair, domain, false)) {
                pair = store.loadAcmeUserKeyPair(); // load key created by another peer concurrently
            }
        }

        return pair;
    }

    public DynamicCertificateState getStateOfCertificate(String id) {
        CertificateData cert = store.loadCertificateForDomain(id);
        if (cert != null) {
            return DynamicCertificateState.valueOf(cert.getState());
        }
        return DynamicCertificateState.WAITING;
    }

    public void setStateOfCertificate(String id, DynamicCertificateState state) {
        CertificateData cert = store.loadCertificateForDomain(id);
        if (cert != null) {
            boolean prevAvail = cert.isAvailable();
            cert.setState(state.name());
            cert.setAvailable(DynamicCertificateState.AVAILABLE.equals(state));
            store.saveCertificate(cert);
            if (prevAvail != cert.isAvailable()) {
                groupMembershipHandler.fireEvent(EVENT_CERT_AVAIL_CHANGED);                
            }
        }
    }

    /**
     *
     * @param domain
     * @return PKCS12 Keystore content
     */
    public byte[] getCertificateForDomain(String domain) throws GeneralSecurityException {
        DynamicCertificate cert = certificates.get(domain); // certs always retrived from cache
        if (cert == null) {
            LOG.log(Level.SEVERE, "No dynamic certificate for domain {0}", domain);
            return null;
        }
        if (!cert.isAvailable()) {
            LOG.log(Level.SEVERE, "Dynamic certificate for domain {0} is not available: {1}", new Object[]{domain, cert});
            return null;
        }
        return Base64.getDecoder().decode(cert.getData().getChain());
    }

    private class CertAvailChangeCallback implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId) {
            LOG.log(Level.INFO, "Certificate availability changed");
            try {
                reloadCertificatesFromDB();
            } catch (Exception err) {
                LOG.log(Level.SEVERE, "Cannot apply new configuration");
            }
        }

        @Override
        public void reconnected() {
            LOG.log(Level.INFO, "Configuration listener - reloading configuration after ZK reconnection");
            try {
                reloadCertificatesFromDB();
            } catch (Exception err) {
                LOG.log(Level.SEVERE, "Cannot apply new configuration");
            }
        }
    }

    private void reloadCertificatesFromDB() {
        try {
            ConcurrentHashMap _certificates = new ConcurrentHashMap();
            for (String domain : certificates.keySet()) {
                _certificates.put(domain, loadOrCreateDynamicCertificateForDomain(domain));
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates from db.", e);
        }
    } 

}
