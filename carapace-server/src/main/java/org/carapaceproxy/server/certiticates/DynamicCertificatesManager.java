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
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.ConfigurationStoreException;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.EXPIRED;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.ORDERING;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.VERIFIED;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.VERIFYING;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.WAITING;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.toolbox.JSON;
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

    private Map<String, CertificateData> certificates = new ConcurrentHashMap();
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

    public void attachGroupMembershipHandler(GroupMembershipHandler groupMembershipHandler) {
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
                    CertificateData cert = loadOrCreateDynamicCertificateForDomain(domain);
                    if (MANUAL == config.getMode()) {
                        cert.setManual(true);
                    }
                    _certificates.put(domain, cert);
                }
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates configuration.", e);
        }
    }

    private CertificateData loadOrCreateDynamicCertificateForDomain(String domain) throws GeneralSecurityException, MalformedURLException {
        CertificateData cert = store.loadCertificateForDomain(domain);
        if (cert == null) {
            cert = new CertificateData(domain, "", "", WAITING.name(), "", "", false);            
        }
        return cert;
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
        if (groupMembershipHandler != null) {
            int acquirePeriod = period;
            if (acquirePeriod <= 0) {
                acquirePeriod = 1;
            }
            groupMembershipHandler.executeInMutex(THREAD_NAME, acquirePeriod, () -> {
                // Only one node of the cluster executes this
                certificatesLifecycle();
            });
        }
    }

    private void certificatesLifecycle() {
        List<String> domains = certificates.entrySet().stream()
                .filter(e -> !e.getValue().isManual())
                .map(e -> e.getKey())
                .sorted()
                .collect(Collectors.toList());
        for (String domain : domains) {          
            boolean updateDB = true;
            boolean notifyCertAvailChanged = false;
            try {
                CertificateData cert = loadOrCreateDynamicCertificateForDomain(domain);
                switch (DynamicCertificateState.valueOf(cert.getState())) {
                    case WAITING: { // certificate waiting to be issues/renew
                        LOG.info("Certificate ISSUING process for domain: " + domain + " STARTED.");
                        Order order = client.createOrderForDomain(domain);
                        cert.setPendingOrderLocation(order.getLocation());
                        LOG.info("Pending order location for domain " + domain + ": " + order.getLocation());
                        Http01Challenge challenge = client.getHTTPChallengeForOrder(order);
                        if (challenge == null) {
                            cert.setState(VERIFIED);
                        } else {
                            LOG.info("Pending challenge data for domain " + domain + ": " + challenge.getJSON());
                            triggerChallenge(challenge);                            
                            cert.setPendingChallengeData(challenge.getJSON());
                            cert.setState(VERIFYING);
                        }
                        break;
                    }
                    case VERIFYING: { // challenge verification by LE pending
                        LOG.info("VERIFYING certificate for domain " + domain + ".");
                        JSON challengeData = null;
                        try {
                            challengeData = JSON.parse(cert.getPendingChallengeData());
                        } catch (Exception e) {
                            LOG.log(Level.SEVERE, "Unable to get pending challenge data for domain " + domain, e);
                            cert.setState(REQUEST_FAILED);
                            break;
                        }
                        LOG.info("CHALLENGE: " + cert.getPendingChallengeData() + ".");
                        Http01Challenge pendingChallenge = new Http01Challenge(client.getLogin(), challengeData);                        
                        Status status = client.checkResponseForChallenge(pendingChallenge); // checks response and updates the challenge
                        cert.setPendingChallengeData(pendingChallenge.getJSON());
                        if (status == Status.VALID) {
                            cert.setState(VERIFIED);
                            store.deleteAcmeChallengeToken(pendingChallenge.getToken());
                        } else if (status == Status.INVALID) {
                            cert.setState(REQUEST_FAILED);
                            store.deleteAcmeChallengeToken(pendingChallenge.getToken());
                        }
                        break;
                    }
                    case VERIFIED: { // challenge succeded
                        LOG.info("Certificate for domain " + domain + " VERIFIED.");
                        URL orderLocation = null;
                        try {
                            orderLocation = new URL(cert.getPendingOrderLocation());
                        } catch (Exception e) {
                            LOG.log(Level.SEVERE, "Unable to manage pending order location for domain " + domain, e);
                            cert.setState(REQUEST_FAILED);
                            break;
                        }
                        Order pendingOrder = client.getLogin().bindOrder(orderLocation);
                        KeyPair keys = loadOrCreateKeyPairForDomain(domain);
                        cert.setPrivateKey(domain);
                        client.orderCertificate(pendingOrder, keys);
                        cert.setState(ORDERING);
                        break;
                    }
                    case ORDERING: { // certificate ordering
                        LOG.info("ORDERING certificate for domain " + domain + ".");
                        URL orderLocation = null;
                        try {
                            orderLocation = new URL(cert.getPendingOrderLocation());
                        } catch (Exception e) {
                            LOG.log(Level.SEVERE, "Unable to manage pending order location for domain " + domain, e);
                            cert.setState(REQUEST_FAILED);
                            break;
                        }
                        Order order = client.getLogin().bindOrder(orderLocation);
                        Status status = client.checkResponseForOrder(order);
                        if (status == Status.VALID) {
                            List<X509Certificate> certificateChain = client.fetchCertificateForOrder(order).getCertificateChain();
                            PrivateKey key = loadOrCreateKeyPairForDomain(domain).getPrivate();
                            String chain = base64EncodeCertificateChain(certificateChain.toArray(new Certificate[0]), key);
                            cert.setChain(chain);
                            cert.setAvailable(true);
                            cert.setState(AVAILABLE);
                            notifyCertAvailChanged = true; // all other peers need to know that this cert is available.
                            LOG.info("Certificate issuing for domain: " + domain + " SUCCEED. Certificate's NOW AVAILABLE.");
                        } else if (status == Status.INVALID) {
                            cert.setState(REQUEST_FAILED);
                        }
                        break;
                    }
                    case REQUEST_FAILED: { // challenge/order failed
                        LOG.info("Certificate issuing for domain: " + domain + " current status is FAILED, setting status=WAITING again.");
                        cert.setState(WAITING);
                        break;
                    }
                    case AVAILABLE: { // certificate saved/available/not expired
                        if (checkCertificateExpired(cert)) {
                            cert.setAvailable(false);
                            cert.setState(EXPIRED);
                            notifyCertAvailChanged = true; // all other peers need to know that this cert is expired.
                        } else {
                            updateDB = false;
                        }
                        break;
                    }
                    case EXPIRED: {     // certificate expired
                        LOG.info("Certificate for domain: " + domain + " EXPIRED.");
                        cert.setState(WAITING);
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
                if (updateDB) {
                    LOG.fine("Save certificate for domain " + domain);
                    store.saveCertificate(cert);
                }
                if (notifyCertAvailChanged) {
                    // remember that events  are not delivered to the local JVM
                    reloadCertificatesFromDB();
                    groupMembershipHandler.fireEvent(EVENT_CERT_AVAIL_CHANGED);
                }
            } catch (AcmeException | IOException | ConfigurationStoreException | GeneralSecurityException | IllegalStateException ex) {
                LOG.log(Level.SEVERE, "Error while handling dynamic certificate for domain " + domain, ex);
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
                pair = store.loadKeyPairForDomain(domain); // load key created by another peer concurrently
            }
        }

        return pair;
    }

    public DynamicCertificateState getStateOfCertificate(String id) {
        if (certificates.containsKey(id)) {
            CertificateData cert = store.loadCertificateForDomain(id);
            if (cert != null) {
                return DynamicCertificateState.valueOf(cert.getState());
            }
        }

        return null;
    }

    public void setStateOfCertificate(String id, DynamicCertificateState state) {
        if (certificates.containsKey(id)) {
            CertificateData cert = store.loadCertificateForDomain(id);
            if (cert != null) {
                boolean prevAvail = cert.isAvailable();
                cert.setState(state.name());
                cert.setAvailable(DynamicCertificateState.AVAILABLE.equals(state));
                store.saveCertificate(cert);
                // remember that events  are not delivered to the local JVM
                reloadCertificatesFromDB();
                if (prevAvail != cert.isAvailable() && groupMembershipHandler!= null) {
                    groupMembershipHandler.fireEvent(EVENT_CERT_AVAIL_CHANGED);
                }
            }
        }
    }

    public static boolean checkCertificateExpired(CertificateData cert) throws GeneralSecurityException {
        try {
            Certificate[] chain = base64DecodeCertificateChain(cert.getChain());
            if (chain != null && chain.length > 0) {
                ((X509Certificate) chain[0]).checkValidity();
            } else {
                return true;
            }
        } catch (CertificateExpiredException | CertificateNotYetValidException ex) {
            return true;
        }
        return false;
    }

    /**
     *
     * @param domain
     * @return PKCS12 Keystore content
     */
    public byte[] getCertificateForDomain(String domain) throws GeneralSecurityException {
        CertificateData cert = certificates.get(domain); // certs always retrived from cache
        if (cert == null) {
            LOG.log(Level.SEVERE, "No dynamic certificate for domain {0}", domain);
            return null;
        }
        if (!cert.isAvailable()) {
            LOG.log(Level.SEVERE, "Dynamic certificate for domain {0} is not available: {1}", new Object[]{domain, cert});
            return null;
        }
        return Base64.getDecoder().decode(cert.getChain());
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
            Map<String, CertificateData> _certificates = new ConcurrentHashMap<>();
            for (String domain : certificates.keySet()) {
                _certificates.put(domain, loadOrCreateDynamicCertificateForDomain(domain));
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates from db.", e);
        }
    }

}
