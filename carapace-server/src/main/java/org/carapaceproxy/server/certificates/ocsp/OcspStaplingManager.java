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
package org.carapaceproxy.server.certificates.ocsp;

import io.netty.handler.ssl.OpenSsl;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;

/**
 * Manager performing:
 *  - periodic OCSP stapling requests
 *  - OCSP responses management
 *
 * @author paolo.venturi
 */
public class OcspStaplingManager implements Runnable {

    public static final String THREAD_NAME = "ocsp-stapling-manager";
    private static final long DAY_SECONDS = 1000 * 60 * 60 * 24;

    private static final boolean TESTING_MODE = Boolean.getBoolean("carapace.ocsp.testmode");
    private static final Logger LOG = Logger.getLogger(OcspStaplingManager.class.getName());
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean started; // keep track of start() calling
    private ConfigurationStore store;
    private volatile int period = 0; // in seconds

    private final ConcurrentHashMap<String, OcspCheck> ocspChecks = new ConcurrentHashMap<>();

    private static final class OcspCheck {

        Certificate[] chain;
        OCSPResp response;

        public OcspCheck(Certificate[] chain, OCSPResp response) {
            this.chain = chain;
            this.response = response;
        }
    }

    public OcspStaplingManager() {
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(THREAD_NAME).build());
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        this.period = newConfiguration.getOcspStaplingManagerPeriod();
    }

    public synchronized void start() {
        started = true;
        if (period <= 0) {
            return;
        }

        LOG.info("Starting " + OcspStaplingManager.class.getName() + ", period: " + period + " seconds" + (TESTING_MODE ? " (TESTING_MODE)" : ""));
        scheduledFuture = scheduler.scheduleWithFixedDelay(this, 0, period, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        started = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            scheduledFuture = null;
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        ocspChecks.entrySet().forEach((ocspCheck) -> {
            String domain = ocspCheck.getKey();
            OcspCheck check = ocspCheck.getValue();
            try {
                if (isExpired(check.response)) {
                    ocspChecks.remove(domain);
                    performStaplingForDomain(domain, check.chain);
                }
            } catch (IOException | OCSPException | GeneralSecurityException ex) {
                LOG.log(Level.SEVERE, "OCSP stapling failed for domain " + domain);
            }
        });
    }

    public static boolean isExpired(OCSPResp response) throws OCSPException {
        SingleResp resp = ((BasicOCSPResp) response.getResponseObject()).getResponses()[0];
        Date nextUpdate = resp.getNextUpdate();
        if (nextUpdate == null) {
            return true;
        }
        Date expiringDate = new Date(); // now
        long daysBetween = TimeUnit.DAYS.convert(nextUpdate.getTime() - expiringDate.getTime(), TimeUnit.MILLISECONDS);
        if (daysBetween >= 1) { // at least a day
            expiringDate = new Date(expiringDate.getTime() + DAY_SECONDS); // tomorrow
        }

        return nextUpdate.before(expiringDate);
    }

    public void performStaplingForDomain(String domain, Certificate[] chain) throws IOException, OCSPException, GeneralSecurityException {
        if (!OpenSsl.isAvailable() || !OpenSsl.isOcspSupported() || chain == null && chain.length == 0) {
            LOG.log(Level.INFO, "OCSP non supported. skipping OCSP stapling");
            return;
        }
        X509Certificate cert = (X509Certificate) chain[0];
        X509Certificate issuer = (X509Certificate) chain[chain.length - 1];

        URI uri = OcspUtils.ocspUri(cert);
        LOG.log(Level.INFO, "OCSP Responder URI: " + uri);

        if (uri == null) {
            LOG.log(Level.INFO, "The CA/certificate doesn't have an OCSP responder, skipping OCSP stapling for domain " + domain);
            return;
        }

        // Step 1: Construct the OCSP request
        OCSPReq request = new OcspRequestBuilder()
                .certificate(cert)
                .issuer(issuer)
                .build();

        // Step 2: Do the request to the CA's OCSP responder
        OCSPResp response = OcspUtils.request(uri, request, 5L, TimeUnit.SECONDS);
        if (response.getStatus() != OCSPResponseStatus.SUCCESSFUL) {
            LOG.log(Level.INFO, "response-status=" + response.getStatus() + ", OCSP stapling failed");
            return;
        }

        // Step 3: Is my certificate any good or has the CA revoked it?
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        SingleResp first = basicResponse.getResponses()[0];

        CertificateStatus status = first.getCertStatus();
        LOG.log(Level.INFO, "Status: " + (status == CertificateStatus.GOOD ? "Good" : status));
        LOG.log(Level.INFO, "This Update: " + first.getThisUpdate());
        LOG.log(Level.INFO, "Next Update: " + first.getNextUpdate());

        BigInteger certSerial = cert.getSerialNumber();
        BigInteger ocspSerial = first.getCertID().getSerialNumber();
        if (!certSerial.equals(ocspSerial)) {
            LOG.log(Level.INFO, "Bad Serials=" + certSerial + " vs. " + ocspSerial + ". Skipping response");
            return;
        }

        // Step 4: Cache the OCSP response and use it as long as it's not expired.
        ocspChecks.put(domain, new OcspCheck(chain, response));
    }

    public byte[] getResponseForDomain(String domain) throws IOException {
        OcspCheck check = ocspChecks.get(domain);
        return check != null ? check.response.getEncoded() : new byte[0];
    }

    public List<OCSPResp> getAllOcspResp() {
        return ocspChecks.values().stream().map(c -> c.response).collect(Collectors.toList());
    }

}
