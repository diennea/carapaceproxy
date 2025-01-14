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

import static java.util.function.Predicate.not;
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
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.utils.CertificatesUtils.isCertificateExpired;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(DynamicCertificatesManager.class);
    private static final String EVENT_CERTIFICATES_STATE_CHANGED = "certificates_state_changed";
    private static final String EVENT_CERTIFICATES_REQUEST_STORE = "certificates_request_store";

    private Map<String, CertificateData> certificates = new ConcurrentHashMap<>();
    private ACMEClient acmeClient; // Let's Encrypt client
    private Route53Client r53Client;
    private String awsAccessKey;
    private String awsSecretKey;
    private final Map<String, Integer> dnsChallengeReachabilityChecks = new ConcurrentHashMap<>();

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
        groupMembershipHandler.watchEvent(EVENT_CERTIFICATES_REQUEST_STORE, new OnCertificatesRequestStore());
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
            final var _certificates = new ConcurrentHashMap<String, CertificateData>();
            for (Entry<String, SSLCertificateConfiguration> e : certificates.entrySet()) {
                SSLCertificateConfiguration config = e.getValue();
                if (config.isDynamic()) {
                    String domain = config.getId(); // hostname or *.hostname
                    if (config.isWildcard() && (awsAccessKey == null || awsSecretKey == null)) {
                        throw new ConfigurationNotValidException(
                                "For ACME wildcards certificates AWS Route53 credentials has to be set"
                        );
                    }
                    boolean forceManual = MANUAL == config.getMode();
                    _certificates.put(domain, loadOrCreateDynamicCertificateForDomain(
                            domain, config.getSubjectAltNames(), forceManual, config.getDaysBeforeRenewal()
                    ));
                }
            }
            this.certificates = _certificates; // only certificates/domains specified in the config have to be managed.
        } catch (GeneralSecurityException | MalformedURLException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates configuration.", e);
        }
    }

    private CertificateData loadOrCreateDynamicCertificateForDomain(String domain,
                                                                    Set<String> subjectAltNames,
                                                                    boolean forceManual,
                                                                    int daysBeforeRenewal) throws GeneralSecurityException, MalformedURLException {
        CertificateData cert = store.loadCertificateForDomain(domain);
        if (cert == null) {
            cert = new CertificateData(domain, subjectAltNames, null, WAITING, null, null);
        } else if (cert.getChain() != null && !cert.getChain().isEmpty()) {
            byte[] keystoreData = Base64.getDecoder().decode(cert.getChain());
            cert.setKeystoreData(keystoreData);
            Certificate[] chain = readChainFromKeystore(keystoreData);
            if (chain != null && chain.length > 0) {
                X509Certificate _cert = ((X509Certificate) chain[0]);
                cert.setExpiringDate(_cert.getNotAfter());
                cert.setSerialNumber(_cert.getSerialNumber().toString(16).toUpperCase()); // HEX
            }
        }
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
        LOG.info("Starting DynamicCertificatesManager, period: {} seconds{}", period, TESTING_MODE ? " (TESTING_MODE)" : "");
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
            // Only one node of the cluster executes this
            groupMembershipHandler.executeInMutex(THREAD_NAME, period > 0 ? period : 1, this::certificatesLifecycle);
        }
    }

    private void certificatesLifecycle() {
        var flushCache = false;
        List<CertificateData> _certificates = certificates.entrySet().stream()
                .filter(e -> !e.getValue().isManual())
                .sorted(Entry.comparingByKey())
                .map(Entry::getValue)
                .toList();
        for (CertificateData data : _certificates) {
            var updateCertificate = true;
            final var domain = data.getDomain();
            try {
                // this has to be always fetch from db!
                CertificateData cert = loadOrCreateDynamicCertificateForDomain(domain, data.getSubjectAltNames(), false, data.getDaysBeforeRenewal());
                switch (cert.getState()) {
                    // certificate waiting to be issues/renew
                    case WAITING -> startCertificateProcessing(domain, cert);
                    // certificate domain reported as unreachable for issuing/renewing
                    case DOMAIN_UNREACHABLE -> {
                        if (cert.getAttemptsCount() <= getConfig().getMaxAttempts()) {
                            startCertificateProcessing(domain, cert);
                        }
                    }
                    // waiting for dns propagation for all dns challenges
                    case DNS_CHALLENGE_WAIT -> checkDnsChallengesReachabilityForCertificate(cert);
                    // challenges verification by LE pending
                    case VERIFYING -> checkChallengesResponsesForCertificate(cert);
                    // challenge succeeded
                    case VERIFIED -> {
                        LOG.info("Certificate for domain {} VERIFIED.", domain);
                        Order pendingOrder = acmeClient.getLogin().bindOrder(cert.getPendingOrderLocation());
                        // if the order is already valid, we have to skip finalization
                        if (pendingOrder.getStatus() != Status.VALID) {
                            try {
                                KeyPair keys = loadOrCreateKeyPairForDomain(domain);
                                acmeClient.orderCertificate(pendingOrder, keys);
                            } catch (AcmeException ex) { // order finalization failed
                                LOG.error("Certificate order finalization for domain {} FAILED.", domain, ex);
                                cert.error(ex.getMessage());
                                break;
                            }
                        }
                        cert.step(ORDERING);
                    }
                    // certificate ordering
                    case ORDERING -> {
                        LOG.info("ORDERING certificate for domain {}.", domain);
                        Order order = acmeClient.getLogin().bindOrder(cert.getPendingOrderLocation());
                        Status status = acmeClient.checkResponseForOrder(order);
                        if (status == Status.VALID) {
                            List<X509Certificate> certificateChain = acmeClient.fetchCertificateForOrder(order).getCertificateChain();
                            PrivateKey key = loadOrCreateKeyPairForDomain(domain).getPrivate();
                            String chain = base64EncodeCertificateChain(certificateChain.toArray(new Certificate[0]), key);
                            cert.setChain(chain);
                            cert.success(AVAILABLE);
                            LOG.info("Certificate issuing for domain: {} SUCCEED. Certificate AVAILABLE.", domain);
                        } else if (status == Status.INVALID) {
                            cert.error("Order status for certificate is " + status);
                        }
                    }
                    // challenge/order failed
                    case REQUEST_FAILED -> {
                        if (cert.getAttemptsCount() <= getConfig().getMaxAttempts()){
                            LOG.info("Certificate issuing for domain: {} current status is FAILED, setting status=WAITING again.", domain);
                            cert.step(WAITING);
                        }
                    }
                    // certificate saved/available/not expired
                    case AVAILABLE -> {
                        if (isCertificateExpired(cert.getExpiringDate(), cert.getDaysBeforeRenewal())) {
                            cert.step(EXPIRED);
                        } else {
                            updateCertificate = false;
                        }
                    }
                    // certificate expired
                    case EXPIRED -> {
                        LOG.info("Certificate for domain: {} EXPIRED.", domain);
                        cert.step(WAITING);
                    }
                    default -> throw new IllegalStateException();
                }
                if (updateCertificate) {
                    LOG.info("Save certificate request status for domain {}", domain);
                    store.saveCertificate(cert);
                    flushCache = true;
                }
            } catch (AcmeException | IOException | GeneralSecurityException | IllegalStateException ex) {
                LOG.error("Error while handling dynamic certificate for domain {}", domain, ex);
            }
        }
        if (flushCache) {
            groupMembershipHandler.fireEvent(EVENT_CERTIFICATES_STATE_CHANGED, null);
            // remember that events  are not delivered to the local JVM
            reloadCertificatesFromDB();
        }
    }

    private void startCertificateProcessing(final String domain, final CertificateData cert) throws AcmeException {
        LOG.info("WAITING for certificate issuing process start for domain: {}.", domain);
        final var unreachableNames = new HashMap<String, String>();
        final var names = cert.getNames();
        LOG.info("Names to check for {} are {}", domain, names);
        for (final var name : names) {
            try {
                if (!CertificatesUtils.isWildcard(name)) {
                    final var unmatchedIps = matchDomainIps(name);
                    if (!unmatchedIps.isEmpty()) {
                        unreachableNames.put(name, "The resolved IPs " + unmatchedIps + " where not expected");
                    }
                }
            } catch (AcmeException | IOException ex) {
                LOG.error("Domain checking failed for {}", name, ex);
                unreachableNames.put(name, ex.getMessage());
            }
        }
        if (unreachableNames.isEmpty()) {
            createOrderAndChallengesForCertificate(cert);
        } else {
            cert.error(DOMAIN_UNREACHABLE, unreachableNames.toString());
        }
    }

    private Set<String> matchDomainIps(String domain) throws AcmeException, UnknownHostException {
        if (!domainsCheckerIPAddresses.isEmpty()) {
            final var allByName = InetAddress.getAllByName(domain);
            LOG.info("Resolved names for {} are {}", domain, allByName);
            return Arrays.stream(allByName)
                    .map(InetAddress::getHostAddress)
                    .filter(not(domainsCheckerIPAddresses::contains))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    /**
     * At the end, we can expect the following states:
     * <ul>
     *     <li>{@link DynamicCertificateState#DNS_CHALLENGE_WAIT} whether there is at least a wildcard domain;</li>
     *     <li>{@link DynamicCertificateState#REQUEST_FAILED} whether there is at least a dns challenge record that cannot be created;</li>
     *     <li>{@link DynamicCertificateState#VERIFYING} otherwise</li>
     * </ul>
     */
    private void createOrderAndChallengesForCertificate(CertificateData cert) throws AcmeException {
        Order order = acmeClient.createOrderForDomain(cert.getNames());
        cert.setPendingOrderLocation(order.getLocation());
        LOG.info("Pending order location for domain {}: {}", cert.getDomain(), cert.getPendingOrderLocation());

        final var challenges = acmeClient.getChallengesForOrder(order);
        if (challenges.isEmpty()) {
            cert.step(VERIFIED);
        } else {
            cert.step(VERIFYING);
            final var challengesData = new HashMap<String, JSON>();
            for (final var e: challenges.entrySet()) {
                final var domain = e.getKey();
                final var challenge = e.getValue();
                challengesData.put(domain, challenge.getJSON());
                LOG.info("Pending challenge data for domain {}: {}", domain, challenge.getJSON());
                if (challenge instanceof final Dns01Challenge dns01Challenge) {
                    if (r53Client.createDnsChallengeForDomain(domain, dns01Challenge.getDigest())) {
                        cert.step(DNS_CHALLENGE_WAIT);
                        LOG.info("Created new TXT DNS challenge-record for domain {}.", domain);
                    } else {
                        cert.error("Creation of TXT DNS challenge-record failed");
                        LOG.error("Creation of TXT DNS challenge-record for domain {} FAILED.", domain);
                        break;
                    }
                } else {
                    final var http01Challenge = (Http01Challenge) challenge;
                    store.saveAcmeChallengeToken(http01Challenge.getToken(), http01Challenge.getAuthorization()); // used for incoming acme-challenge requests
                    http01Challenge.trigger();
                }
            }
            if (cert.getState() == REQUEST_FAILED) {
                LOG.info("Cleaning up all challenges for certificate {}", cert);
                challenges.forEach(this::cleanupChallengeForDomain);
            } else {
                cert.setPendingChallengesData(challengesData);
            }
        }
    }

    private void checkDnsChallengesReachabilityForCertificate(CertificateData cert) throws AcmeException {
        var totalCount = 0;
        var okCount = 0;
        cert.step(DNS_CHALLENGE_WAIT);
        final var challenges = getChallengesFromCertificate(cert).entrySet();
        for (var c: challenges) {
            if (!(c.getValue() instanceof final Dns01Challenge challenge)) {
                continue;
            }
            totalCount++;
            final var domain = c.getKey();
            LOG.info("DNS CHALLENGE WAITING for domain {}: {}.", domain, challenge);
            if (r53Client.isDnsChallengeForDomainAvailable(domain, challenge.getDigest())) {
                okCount++;
                dnsChallengeReachabilityChecks.remove(domain);
                challenge.trigger();
            } else {
                Integer value = dnsChallengeReachabilityChecks.getOrDefault(domain, 0);
                if (++value >= DNS_CHALLENGE_REACHABILITY_CHECKS_LIMIT) {
                    LOG.info("Too many reachability attempts of TXT DNS challenge-record for domain {}: {}", domain, challenge);
                    cert.error("Too many reachability attempts of TXT DNS challenge-record: " + value);
                } else {
                    dnsChallengeReachabilityChecks.put(domain, value);
                }
            }
        }
        if (cert.getState() == REQUEST_FAILED) {
            LOG.info("Cleaning up all challenges for certificate {}", cert);
            for (var c: challenges) {
                final var domain = c.getKey();
                dnsChallengeReachabilityChecks.remove(domain);
                cleanupChallengeForDomain(domain, c.getValue());
            }
        } else if (okCount == totalCount) {
            cert.step(VERIFYING);
        }
    }

    private Map<String, Challenge> getChallengesFromCertificate(CertificateData cert) throws AcmeException {
        final var challengesData = new HashMap<String, Challenge>();
        final var login = acmeClient.getLogin();
        for (final var e: cert.getPendingChallengesData().entrySet()) {
            final var domain = e.getKey();
            final var challenge = e.getValue();
            LOG.debug("Challenge data for domain {}: {}", domain, challenge);
            challengesData.put(domain, CertificatesUtils.isWildcard(domain)
                    ? new Dns01Challenge(login, challenge)
                    : new Http01Challenge(login, challenge)
            );
        }
        return challengesData;
    }

    private void checkChallengesResponsesForCertificate(CertificateData cert) throws AcmeException {
        var okCount = 0;
        cert.step(VERIFYING);
        final var challenges = getChallengesFromCertificate(cert).entrySet();
        for (var c: challenges) {
            final var domain = c.getKey();
            final var challenge = c.getValue();
            LOG.info("VERIFYING challenge response for domain {}: {}", domain, challenge);
            final var status = acmeClient.checkResponseForChallenge(challenge); // checks response and updates the challenge
            cert.getPendingChallengesData().put(domain, challenge.getJSON());
            if (status == Status.VALID) {
                okCount++;
                cleanupChallengeForDomain(domain, challenge);
            } else if (status == Status.INVALID) {
                cert.error("Challenge response verification failed, status is " + status);
            }
        }
        if (cert.getState() == REQUEST_FAILED) {
            LOG.info("Cleaning up all challenges for certificate {}", cert);
            challenges.forEach(c -> cleanupChallengeForDomain(c.getKey(), c.getValue()));
        } else if (okCount == challenges.size()) {
            cert.step(VERIFIED);
        }
    }

    private void cleanupChallengeForDomain(String domain, Challenge challenge) {
        if (challenge instanceof final Dns01Challenge ch) {
            if (r53Client.deleteDnsChallengeForDomain(domain, ch.getDigest())) {
                LOG.info("DELETE TXT DNS challenge-record for domain {}: {}", domain, challenge);
            } else {
                LOG.info("Deletion of TXT DNS challenge-record FAILED for domain {}: {}", domain, challenge);
            }
        } else {
            final var ch = (Http01Challenge) challenge;
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

    @VisibleForTesting
    public DynamicCertificateState getStateOfCertificate(String id) {
        if (certificates.containsKey(id)) {
            CertificateData cert = store.loadCertificateForDomain(id);
            if (cert != null) {
                return cert.getState();
            }
        }

        return null;
    }

    @VisibleForTesting
    public void setStateOfCertificate(String id, DynamicCertificateState state) {
        if (certificates.containsKey(id)) {
            CertificateData cert = store.loadCertificateForDomain(id);
            if (cert != null) {
                cert.success(state);
                store.saveCertificate(cert);
                // remember that events are not delivered to the local JVM
                reloadCertificatesFromDB();
                if (groupMembershipHandler != null) {
                    groupMembershipHandler.fireEvent(EVENT_CERTIFICATES_STATE_CHANGED, null);
                }
            }
        }
    }

    private RuntimeServerConfiguration getConfig() {
        return server.getCurrentConfiguration();
    }

    /**
     * @param domain the domain to resolve certificate keystore for
     * @return PKCS12 Keystore content
     */
    public byte[] getCertificateForDomain(String domain) {
        CertificateData cert = certificates.get(domain); // certs always retrieved from cache
        if (cert == null || cert.getKeystoreData() == null || cert.getKeystoreData().length == 0) {
            LOG.error("No dynamic certificate available for domain {}", domain);
            return null;
        }
        return cert.getKeystoreData();
    }

    public CertificateData getCertificateDataForDomain(String domain) {
        return certificates.get(domain);
    }

    private class OnCertificatesStateChanged implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId, Map<String, Object> data) {
            LOG.info("Certificates state changed");
            try {
                reloadCertificatesFromDB();
            } catch (Exception err) {
                LOG.error("Failed to reload certificates from db. Cause: {0}", err);
            }
        }

        @Override
        public void reconnected() {
            LOG.info("Reloading certificates after ZK reconnection");
            try {
                reloadCertificatesFromDB();
            } catch (Exception err) {
                LOG.error("Failed to reload certificates from db. Cause: {0}", err);
            }
        }
    }

    private class OnCertificatesRequestStore implements GroupMembershipHandler.EventCallback {

        @Override
        public void eventFired(String eventId, Map<String, Object> data) {
            LOG.info("Certificates request store");
            try {
                final var certs = (List<?>) data.get("certs");
                final var _certificates = certs.isEmpty()
                        ? DynamicCertificatesManager.this.certificates.values()
                        : certs.stream()
                                .map(String.class::cast)
                                .map(DynamicCertificatesManager.this::getCertificateDataForDomain)
                                .collect(Collectors.toList());
                storeLocalCertificates(_certificates, null);
            } catch (Exception err) {
                LOG.error("Failed to store certificates. Cause: {0}", err);
            }
        }

        @Override
        public void reconnected() {
        }
    }

    private void reloadCertificatesFromDB() {
        LOG.info("Reloading certificates from db");
        try {
            Map<String, CertificateData> newCertificates = new ConcurrentHashMap<>();
            for (Entry<String, CertificateData> entry : certificates.entrySet()) {
                String domain = entry.getKey();
                CertificateData cert = entry.getValue();
                // "wildcard" and "manual" flags and "daysBeforeRenewal" are not stored in db > have to be re-set from existing config
                CertificateData freshCert = loadOrCreateDynamicCertificateForDomain(domain, cert.getSubjectAltNames(), cert.isManual(), cert.getDaysBeforeRenewal());
                newCertificates.put(domain, freshCert);
                LOG.info("RELOADED certificate for domain {}: {}", domain, freshCert);
            }
            var oldCertificates = this.certificates;
            this.certificates = newCertificates; // only certificates/domains specified in the config have to be managed.
            server.getListeners().reloadCurrentConfiguration();
            // storing certificates on local path
            storeLocalCertificates(newCertificates.values(), oldCertificates);
        } catch (GeneralSecurityException | MalformedURLException | InterruptedException | ConfigurationNotValidException e) {
            throw new DynamicCertificatesManagerException("Unable to load dynamic certificates from db.", e);
        }
    }

    private void storeLocalCertificates(Collection<CertificateData> newCertificates, Map<String, CertificateData> oldCertificates) {
        if (getConfig().getLocalCertificatesStorePath() == null
                || (!getConfig().getLocalCertificatesStorePeersIds().isEmpty()
                && !getConfig().getLocalCertificatesStorePeersIds().contains(server.getPeerId()))) {
            return;
        }

        newCertificates.stream().filter(cert -> {
            CertificateData oldCert = oldCertificates != null ? oldCertificates.get(cert.getDomain()) : null;
            return cert != null && !cert.isManual() && (oldCert == null || (cert.getState() == AVAILABLE && oldCert.getState() != AVAILABLE));
        }).forEach(cert -> {
            final var domainDir = CertificatesUtils.removeWildcard(cert.getDomain());
            var parent = new File(getConfig().getLocalCertificatesStorePath(), domainDir);
            parent.mkdirs();
            LOG.info("Store certificate {} to local path {}", cert.getDomain(), parent.getAbsolutePath());
            try (var fos = new FileOutputStream(new File(parent, "privatekey.pem"));
                    var osw = new OutputStreamWriter(fos);
                    var writer = new PemWriter(osw)) {
                var key = loadOrCreateKeyPairForDomain(cert.getDomain()).getPrivate();
                writer.writeObject(new PemObject("", key.getEncoded()));
            } catch (IOException ex) {
                LOG.error("Failed to save privatekey for certificate domain {} to local path {}", cert.getDomain(), parent.getAbsolutePath());
            }
            try (var fos = new FileOutputStream(new File(parent, "chain.pem"));
                    var fos2 = new FileOutputStream(new File(parent, "fullchain.pem"));
                    var osw = new OutputStreamWriter(fos);
                    var osw2 = new OutputStreamWriter(fos2);
                    var writer = new PemWriter(osw);
                    var writer2 = new PemWriter(osw2)) {
                var chain = base64DecodeCertificateChain(cert.getChain());
                for (int i = 0; i < chain.length; i++) {
                    byte[] encoded = chain[i].getEncoded();
                    if (i > 0) {
                        writer.writeObject(new PemObject("", encoded));
                    }
                    writer2.writeObject(new PemObject("", encoded));
                }
            } catch (IOException ex) {
                LOG.error("Failed to save chains for certificate domain {} to local path {}", cert.getDomain(), parent.getAbsolutePath());
            } catch (GeneralSecurityException ex) {
                LOG.error("Failed to read certificate data for domain {}", cert.getDomain());
            }
        });
    }

    public void forceStoreLocalCertificates(String... certs) {
        final var _certs = Arrays.asList(certs);
        groupMembershipHandler.fireEvent(EVENT_CERTIFICATES_REQUEST_STORE, Map.of("certs", _certs));

        // remember that events  are not delivered to the local JVM
        final var _certificates = _certs.isEmpty()
                ? this.certificates.values()
                : _certs.stream()
                        .map(this::getCertificateDataForDomain)
                        .collect(Collectors.toList());

        storeLocalCertificates(_certificates, null);
    }
}
