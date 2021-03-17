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

import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.DNS_CHALLENGE_WAIT;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.DOMAIN_UNREACHABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.EXPIRED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.ORDERING;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.REQUEST_FAILED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.VERIFIED;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.VERIFYING;
import com.google.common.annotations.VisibleForTesting;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.utils.CertificatesUtils.isCertificateExpired;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
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
    public static final int DEFAULT_KEYPAIRS_SIZE = Integer.getInteger("carapace.acme.default.keypairssize", 2048);
    public static final int DEFAULT_DAYS_BEFORE_RENEWAL = Integer.getInteger("carapace.acme.default.daysbeforerenewal", 30);
    private static final boolean TESTING_MODE = Boolean.getBoolean("carapace.acme.testmode");

    private static final int DNS_CHALLENGE_REACHABILITY_CHECKS_LIMIT = Integer.getInteger("carapace.acme.dnschallengereachabilitycheck.limit", 10);

    private static final Logger LOG = Logger.getLogger(DynamicCertificatesManager.class.getName());
    private static final String EVENT_CERTIFICATES_STATE_CHANGED = "certificates_state_changed";

    private Map<String, CertificateData> certificates = new ConcurrentHashMap();
    private ACMEClient acmeClient; // Let's Encrypt client
    private Route53Client r53Client;
    private String awsAccessKey;
    private String awsSecretKey;
    private final Map<String, Integer> dnsChallegeReachabilityChecks = new ConcurrentHashMap();

    private Set<String> domainsCheckerIPAddresses;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean started; // keep track of start() calling

    private ConfigurationStore store;
    private volatile int period = 0; // in seconds
    private volatile int keyPairsSize = DEFAULT_KEYPAIRS_SIZE;
    private GroupMembershipHandler groupMembershipHandler;
    private final HttpProxyServer server;

    public DynamicCertificatesManager(HttpProxyServer server) {
        this.server = server;
    }

    public void setConfigurationStore(ConfigurationStore configStore) {
        this.store = configStore;
    }

    @VisibleForTesting
    public ConfigurationStore getConfigurationStore() {
        return store;
    }

    public void attachGroupMembershipHandler(GroupMembershipHandler groupMembershipHandler) {
        this.groupMembershipHandler = groupMembershipHandler;
        groupMembershipHandler.watchEvent(EVENT_CERTIFICATES_STATE_CHANGED, new OnCertificatesStateChanged());
    }

    @VisibleForTesting
    public int getPeriod() {
        return period;
    }

    @VisibleForTesting
    public void setPeriod(int period) {
        this.period = period;
    }

    public void initAWSClient(String awsAccessKey, String awsSecretKey) {
        this.awsAccessKey = awsAccessKey;
        this.awsSecretKey = awsSecretKey;
        if (r53Client != null) {
            r53Client.close();
            r53Client = null;
        }
        if (awsAccessKey != null && awsSecretKey != null) {
            r53Client = new Route53Client(awsAccessKey, awsSecretKey);
        }
    }

    public synchronized void reloadConfiguration(RuntimeServerConfiguration configuration) throws ConfigurationNotValidException {
        if (store == null) {
            throw new DynamicCertificatesManagerException("ConfigurationStore not set.");
        }
        keyPairsSize = configuration.getKeyPairsSize();
        if (acmeClient == null) {
            acmeClient = new ACMEClient(loadOrCreateAcmeUserKeyPair(), TESTING_MODE);
        }
        domainsCheckerIPAddresses = configuration.getDomainsCheckerIPAddresses();
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

    private void loadCertificates(Map<String, SSLCertificateConfiguration> certificates) throws ConfigurationNotValidException {
        try {
            ConcurrentHashMap _certificates = new ConcurrentHashMap();
            for (Entry<String, SSLCertificateConfiguration> e : certificates.entrySet()) {
                SSLCertificateConfiguration config = e.getValue();
                if (config.isDynamic()) {
                    String domain = config.getId(); // hostname or *.hostname
                    boolean wildcard = config.isWildcard();
                    if (wildcard && (awsAccessKey == null || awsSecretKey == null)) {
                        throw new ConfigurationNotValidException(
                                "For ACME wildcards certificates AWS Route53 credentials has to be set"
                        );
                    }
                    boolean forceManual = MANUAL == config.getMode();
                    _certificates.put(domain, loadOrCreateDynamicCertificateForDomain(
                            domain, wildcard, forceManual, config.getDaysBeforeRenewal()
                    ));
                }
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates configuration.", e);
        }
    }

    private CertificateData loadOrCreateDynamicCertificateForDomain(String domain,
                                                                    boolean wildcard,
                                                                    boolean forceManual,
                                                                    int daysBeforeRenewal) throws GeneralSecurityException, MalformedURLException {
        CertificateData cert = store.loadCertificateForDomain(domain);
        if (cert == null) {
            cert = new CertificateData(domain, "", "", WAITING, "", "");
        }
        cert.setWildcard(wildcard);
        cert.setManual(forceManual);
        if (!forceManual) { // only for ACME
            cert.setDaysBeforeRenewal(daysBeforeRenewal);
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
        LOG.log(Level.INFO, "Starting DynamicCertificatesManager, period: {0} seconds{1}", new Object[]{period, TESTING_MODE ? " (TESTING_MODE)" : ""});
        scheduledFuture = scheduler.scheduleWithFixedDelay(this, 0, period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        started = false;
        if (scheduler != null) {
            scheduler.shutdown();
            if (r53Client != null) {
                r53Client.close();
            }
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
        boolean flushCache = false;
        List<CertificateData> _certificates = certificates.entrySet().stream()
                .filter(e -> !e.getValue().isManual())
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                .map(e -> e.getValue())
                .collect(Collectors.toList());
        for (CertificateData data : _certificates) {
            boolean updateCertificate = true;
            final String domain = data.getDomain();
            try {
                CertificateData cert = loadOrCreateDynamicCertificateForDomain(domain, data.isWildcard(), false, data.getDaysBeforeRenewal());
                switch (cert.getState()) {
                    case WAITING: // certificate waiting to be issues/renew
                    case DOMAIN_UNREACHABLE: { // certificate domain reported as unreachable for issuing/renewing
                        LOG.log(Level.INFO, "WAITING for certificate issuing process start for domain: {0}.", domain);
                        if (checkDomain(domain)) {
                            Order order = createOrderForCertificate(cert);
                            createChallengeForCertificateOrder(cert, order);
                        } else {
                            cert.setState(DOMAIN_UNREACHABLE);
                        }
                        break;
                    }
                    case DNS_CHALLENGE_WAIT: { // waiting for full dns propagation
                        LOG.log(Level.INFO, "DNS CHALLENGE WAITING for domain {0}.", domain);
                        Dns01Challenge pendingChallenge = (Dns01Challenge) getChallengeFromCertificate(cert);
                        checkDnsChallengeReachabilityForCertificate(pendingChallenge, cert);
                        break;
                    }
                    case VERIFYING: { // challenge verification by LE pending
                        LOG.log(Level.INFO, "VERIFYING certificate for domain {0}.", domain);
                        Challenge pendingChallenge = getChallengeFromCertificate(cert);
                        checkChallengeResponseForCertificate(pendingChallenge, cert);
                        break;
                    }
                    case VERIFIED: { // challenge succeded
                        LOG.log(Level.INFO, "Certificate for domain {0} VERIFIED.", domain);
                        Order pendingOrder = acmeClient.getLogin().bindOrder(new URL(cert.getPendingOrderLocation()));
                        if (pendingOrder.getStatus() != Status.VALID) { // whether the order is already valid we have to skip finalization
                            try {
                                KeyPair keys = loadOrCreateKeyPairForDomain(domain);
                                acmeClient.orderCertificate(pendingOrder, keys);
                            } catch (AcmeException ex) { // order finalization failed
                                LOG.log(Level.SEVERE, "Certificate order finalization for domain {0} FAILED.", domain);
                                cert.setState(REQUEST_FAILED);
                                break;
                            }
                        }
                        cert.setState(ORDERING);
                        break;
                    }
                    case ORDERING: { // certificate ordering
                        LOG.log(Level.INFO, "ORDERING certificate for domain {0}.", domain);
                        Order order = acmeClient.getLogin().bindOrder(new URL(cert.getPendingOrderLocation()));
                        Status status = acmeClient.checkResponseForOrder(order);
                        if (status == Status.VALID) {
                            List<X509Certificate> certificateChain = acmeClient.fetchCertificateForOrder(order).getCertificateChain();
                            PrivateKey key = loadOrCreateKeyPairForDomain(domain).getPrivate();
                            String chain = base64EncodeCertificateChain(certificateChain.toArray(new Certificate[0]), key);
                            cert.setChain(chain);
                            cert.setState(AVAILABLE);
                            LOG.log(Level.INFO, "Certificate issuing for domain: {0} SUCCEED. Certificate AVAILABLE.", domain);
                        } else if (status == Status.INVALID) {
                            cert.setState(REQUEST_FAILED);
                        }
                        break;
                    }
                    case REQUEST_FAILED: { // challenge/order failed
                        LOG.log(Level.INFO, "Certificate issuing for domain: {0} current status is FAILED, setting status=WAITING again.", domain);
                        cert.setState(WAITING);
                        break;
                    }
                    case AVAILABLE: { // certificate saved/available/not expired
                        if (isCertificateExpired(base64DecodeCertificateChain(cert.getChain()), cert.getDaysBeforeRenewal())) {
                            cert.setState(EXPIRED);
                        } else {
                            updateCertificate = false;
                        }
                        break;
                    }
                    case EXPIRED: {     // certificate expired
                        LOG.log(Level.INFO, "Certificate for domain: {0} EXPIRED.", domain);
                        cert.setState(WAITING);
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
                if (updateCertificate) {
                    LOG.log(Level.INFO, "Save certificate request status for domain {0}", domain);
                    store.saveCertificate(cert);
                    flushCache = true;
                }
            } catch (AcmeException | IOException | GeneralSecurityException | IllegalStateException ex) {
                LOG.log(Level.SEVERE, "Error while handling dynamic certificate for domain " + domain, ex);
            }
        }
        if (flushCache) {
            groupMembershipHandler.fireEvent(EVENT_CERTIFICATES_STATE_CHANGED);
            // remember that events  are not delivered to the local JVM
            reloadCertificatesFromDB();
        }
    }

    private boolean checkDomain(String domain) throws AcmeException {
        if (!domainsCheckerIPAddresses.isEmpty()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(domain)) {
                    if (!domainsCheckerIPAddresses.contains(address.getHostAddress())) {
                        return false;
                    }
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "Domain checking failed for {0}: {1}", new Object[]{domain, ex});
                return false;
            }
        }

        return true;
    }

    private Order createOrderForCertificate(CertificateData cert) throws AcmeException {
        Order order = acmeClient.createOrderForDomain(cert.getDomain());
        cert.setPendingOrderLocation(order.getLocation());
        LOG.log(Level.INFO, "Pending order location for domain {0}: {1}", new Object[]{cert.getDomain(), order.getLocation()});

        return order;
    }

    private void createChallengeForCertificateOrder(CertificateData cert, Order order) throws AcmeException {
        Challenge challenge = acmeClient.getChallengeForOrder(order, cert.isWildcard());
        if (challenge == null) {
            cert.setState(VERIFIED);
        } else {
            LOG.log(Level.INFO, "Pending challenge data for domain {0}: {1}", new Object[]{cert.getDomain(), challenge.getJSON()});
            if (cert.isWildcard()) {
                Dns01Challenge ch = (Dns01Challenge) challenge;
                if (r53Client.createDnsChallengeForDomain(cert.getDomain(), ch.getDigest())) {
                    cert.setState(DNS_CHALLENGE_WAIT);
                    LOG.log(Level.INFO, "Created new TXT DNS challenge-record for domain {0}.", cert.getDomain());
                } else {
                    LOG.log(Level.INFO, "Creation of TXT DNS challenge-record for domain {0} FAILED.", cert.getDomain());
                }
            } else {
                Http01Challenge ch = (Http01Challenge) challenge;
                store.saveAcmeChallengeToken(ch.getToken(), ch.getAuthorization()); // used for incoming acme-challenge requests
                challenge.trigger();
                cert.setState(VERIFYING);
            }
            cert.setPendingChallengeData(challenge.getJSON());
        }
    }

    private Challenge getChallengeFromCertificate(CertificateData cert) throws AcmeException {
        JSON challengeData = JSON.parse(cert.getPendingChallengeData());
        LOG.log(Level.FINE, "CHALLENGE: {0}.", challengeData);
        return cert.isWildcard()
                ? new Dns01Challenge(acmeClient.getLogin(), challengeData)
                : new Http01Challenge(acmeClient.getLogin(), challengeData);
    }

    private void checkDnsChallengeReachabilityForCertificate(Dns01Challenge challenge, CertificateData cert) throws AcmeException {
        String domain = cert.getDomain();
        if (r53Client.isDnsChallengeForDomainAvailable(domain, challenge.getDigest())) {
            cert.setState(VERIFYING);
            dnsChallegeReachabilityChecks.remove(domain);
            challenge.trigger();
        } else {
            Integer value = dnsChallegeReachabilityChecks.getOrDefault(domain, 0);
            if (++value >= DNS_CHALLENGE_REACHABILITY_CHECKS_LIMIT) {
                LOG.log(Level.INFO, "Too many reachability attempts of TXT DNS challenge-record for domain {0}.", cert.getDomain());
                cert.setState(REQUEST_FAILED);
                dnsChallegeReachabilityChecks.remove(domain);
                cleanupChallengeForCertificate(challenge, cert);
            } else {
                dnsChallegeReachabilityChecks.put(domain, value);
            }
        }
    }

    private void checkChallengeResponseForCertificate(Challenge challenge, CertificateData cert) throws AcmeException {
        Status status = acmeClient.checkResponseForChallenge(challenge); // checks response and updates the challenge
        cert.setPendingChallengeData(challenge.getJSON());
        if (status == Status.VALID) {
            cert.setState(VERIFIED);
            cleanupChallengeForCertificate(challenge, cert);
        } else if (status == Status.INVALID) {
            cert.setState(REQUEST_FAILED);
            cleanupChallengeForCertificate(challenge, cert);
        }
    }

    private void cleanupChallengeForCertificate(Challenge challenge, CertificateData cert) {
        if (cert.isWildcard()) {
            Dns01Challenge ch = (Dns01Challenge) challenge;
            if (r53Client.deleteDnsChallengeForDomain(cert.getDomain(), ch.getDigest())) {
                LOG.log(Level.INFO, "DELETE TXT DNS challenge-record for domain {0}.", cert.getDomain());
            } else {
                LOG.log(Level.INFO, "Deletion of TXT DNS challenge-record for domain {0} FAILED.", cert.getDomain());
            }
        } else {
            Http01Challenge ch = (Http01Challenge) challenge;
            store.deleteAcmeChallengeToken(ch.getToken());
        }
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
                return cert.getState();
            }
        }

        return null;
    }

    public void setStateOfCertificate(String id, DynamicCertificateState state) {
        if (certificates.containsKey(id)) {
            CertificateData cert = store.loadCertificateForDomain(id);
            if (cert != null) {
                cert.setState(state);
                store.saveCertificate(cert);
                // remember that events  are not delivered to the local JVM
                reloadCertificatesFromDB();
                if (groupMembershipHandler != null) {
                    groupMembershipHandler.fireEvent(EVENT_CERTIFICATES_STATE_CHANGED);
                }
            }
        }
    }

    /**
     *
     * @param domain
     * @return PKCS12 Keystore content
     */
    public byte[] getCertificateForDomain(String domain) throws GeneralSecurityException {
        CertificateData cert = certificates.get(domain); // certs always retrived from cache
        if (cert == null || cert.getChain() == null || cert.getChain().isEmpty()) {
            LOG.log(Level.SEVERE, "No dynamic certificate available for domain {0}", domain);
            return null;
        }
        return Base64.getDecoder().decode(cert.getChain());
    }

    public CertificateData getCertificateDataForDomain(String domain) throws GeneralSecurityException {
        return certificates.get(domain);
    }

    private class OnCertificatesStateChanged implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId) {
            LOG.log(Level.INFO, "Certificates state changed");
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
        LOG.log(Level.INFO, "Reloading certificates from db");
        try {
            Map<String, CertificateData> _certificates = new ConcurrentHashMap<>();
            for (Entry<String, CertificateData> entry : certificates.entrySet()) {
                String domain = entry.getKey();
                CertificateData cert = entry.getValue();
                // "wildcard" and "manual" flags and "daysBeforeRenewal" are not stored in db > have to be re-set from existing config
                CertificateData freshCert = loadOrCreateDynamicCertificateForDomain(domain, cert.isWildcard(), cert.isManual(), cert.getDaysBeforeRenewal());
                _certificates.put(domain, freshCert);
                LOG.log(Level.INFO, "RELOADED certificate for domain {0}: {1}", new Object[]{domain, freshCert});
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
            server.getListeners().reloadCurrentConfiguration();
        } catch (GeneralSecurityException | MalformedURLException | InterruptedException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates from db.", e);
        }
    }

}
