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

import com.google.common.annotations.VisibleForTesting;
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
import java.util.stream.Collectors;
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.OperatorCreationException;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.core.TrustStoreManager;
import org.glassfish.jersey.internal.guava.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager performing: - periodic OCSP stapling requests - OCSP responses management
 *
 * @author paolo.venturi
 */
public class OcspStaplingManager implements Runnable {

    public static final String THREAD_NAME = "ocsp-stapling-manager";
    private static final long DAY_SECONDS = 1000 * 60 * 60 * 24;

    private static final Logger LOG = LoggerFactory.getLogger(OcspStaplingManager.class);
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean started; // keep track of start() calling
    private volatile int period = 0; // in seconds

    private final ConcurrentHashMap<Certificate, OcspCheck> ocspChecks = new ConcurrentHashMap<>();
    private TrustStoreManager trustStoreManager;

    private static final class OcspCheck {

        Certificate[] chain;
        OCSPResp response;

        public OcspCheck(Certificate[] chain, OCSPResp response) {
            this.chain = chain;
            this.response = response;
        }
    }

    public OcspStaplingManager(TrustStoreManager trustStoreManager) {
        this.trustStoreManager = trustStoreManager;
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat(THREAD_NAME).build());
    }

    public synchronized void reloadConfiguration(RuntimeServerConfiguration configuration) {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        period = configuration.getOcspStaplingManagerPeriod();
        ocspChecks.clear();
        if (scheduledFuture != null || started) {
            start();
        }
    }

    public synchronized void start() {
        started = true;
        if (period <= 0) {
            return;
        }

        LOG.info("Starting {}, period: {} seconds", OcspStaplingManager.class.getCanonicalName(), period);
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
        ocspChecks.values().forEach((check) -> {
            X509Certificate cert = (X509Certificate) check.chain[0];
            String dn = cert.getSubjectDN().getName();
            try {
                if (check.response == null || isExpired(check.response)) { // new or expired
                    LOG.info("Performing OCSP stapling for {}", dn);
                    if (!performStaplingForCertificate(check.chain)) {
                        LOG.error("OCSP stapling failed for {}", dn);
                    }
                }
            } catch (IOException | OCSPException | GeneralSecurityException | OperatorCreationException ex) {
                LOG.error("Unable to perform OCSP stapling for {}", dn, ex);
            }
        });
    }

    public static boolean isExpired(OCSPResp response) throws OCSPException {
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        if (basicResponse == null || basicResponse.getResponses().length == 0) {
            throw new OCSPException("Unable to check expiration with no OCSP Response");
        }
        Date nextUpdate = basicResponse.getResponses()[0].getNextUpdate();
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

    public boolean performStaplingForCertificate(Certificate[] chain) throws IOException, OCSPException, GeneralSecurityException, OperatorCreationException {
        if (!OpenSsl.isAvailable() || !OpenSsl.isOcspSupported()) {
            return false;
        }

        if (chain == null || chain.length == 0) {
            return false;
        }

        X509Certificate cert = (X509Certificate) chain[0];
        X509Certificate issuer = OcspUtils.getIssuerCertificate(chain, trustStoreManager.getCertificateAuthorities());
        if (issuer == null) {
            LOG.info("Unable to obtain certicate of issuer {} for certificate subject {}", cert.getIssuerX500Principal().getName(), cert.getSubjectX500Principal().getName());
            return false;
        }
        String dn = cert.getSubjectDN().getName();

        URI uri = OcspUtils.getOcspUri(cert);
        LOG.info("OCSP Responder URI: {}", uri);

        if (uri == null) {
            LOG.info("The CA/certificate doesn't have an OCSP responder, skipping OCSP stapling for {}", dn);
            return false;
        }

        // Step 1: Construct the OCSP request
        OCSPReq request = new OcspRequestBuilder()
                .certificate(cert)
                .issuer(issuer)
                .build();

        // Step 2: Do the request to the CA's OCSP responder
        OCSPResp response = OcspUtils.request(dn, uri, request, 5L, TimeUnit.SECONDS);
        if (response.getStatus() != OCSPResponseStatus.SUCCESSFUL) {
            LOG.error("response-status={}, OCSP stapling failed", response.getStatus());
            return false;
        }

        // Step 3: Get the OCSP response
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        SingleResp first = basicResponse.getResponses()[0];

        CertificateStatus status = first.getCertStatus();
        LOG.info("Status: {}", status == CertificateStatus.GOOD ? "Good" : status);
        LOG.info("This Update: {}", first.getThisUpdate());
        LOG.info("Next Update: {}", first.getNextUpdate());

        BigInteger certSerial = cert.getSerialNumber();
        BigInteger ocspSerial = first.getCertID().getSerialNumber();
        if (!certSerial.equals(ocspSerial)) {
            LOG.info("Bad Serials={} vs. {}. Skipping response", certSerial, ocspSerial);
            return false;
        }

        // Step 4: Cache the OCSP response and use it as long as it's not expired.
        ocspChecks.replace(cert, new OcspCheck(chain, response));
        return true;
    }

    public void addCertificateForStapling(Certificate[] chain) {
        ocspChecks.putIfAbsent(chain[0], new OcspCheck(chain, null));
    }

    public byte[] getOcspResponseForCertificate(Certificate cert) throws IOException {
        OcspCheck check = ocspChecks.get(cert);
        return check != null && check.response != null ? check.response.getEncoded() : new byte[0];
    }

    @VisibleForTesting
    public List<OCSPResp> getAllOcspResp() {
        return ocspChecks.values().stream().map(c -> c.response).collect(Collectors.toList());
    }

}
