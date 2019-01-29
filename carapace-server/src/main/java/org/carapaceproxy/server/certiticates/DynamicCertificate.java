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

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.carapaceproxy.configstore.CertificateData;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodePrivateKey;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certiticates.DynamicCertificate.DynamicCertificateState.WAITING;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.challenge.Challenge;

/**
 *
 * SSL certificate issued via ACME
 *
 * @author paolo.venturi
 */
public final class DynamicCertificate {

    public static enum DynamicCertificateState {
        WAITING, // certificate waiting for issuing/renews
        VERIFYING, // challenge verification by LE pending
        VERIFIED, // challenge succeded
        ORDERING, // certificate order pending
        REQUEST_FAILED, // challenge/order failed
        AVAILABLE, // certificate available(saved) and not expired
        EXPIRED     // certificate expired
    }

    private final String domain;
    private volatile boolean available;
    private volatile DynamicCertificateState state;

    private Order order;
    private Challenge challenge;
    private Certificate[] chain;
    private KeyPair keyPair;

    public DynamicCertificate(String domain) {
        this.domain = domain;
        available = false;
        state = WAITING;
    }

    public DynamicCertificate(CertificateData data) throws GeneralSecurityException {
        domain = data.getDomain();
        available = data.isAvailable();
        state = available ? AVAILABLE : WAITING;
        // Certificate decoding
        this.chain = base64DecodeCertificateChain(data.getChain());
        // Private key decoding + keypar generation
        PrivateKey privateKey = base64DecodePrivateKey(data.getPrivateKey());
        this.keyPair = new KeyPair(chain[0].getPublicKey(), privateKey);
    }

    public String getDomain() {
        return domain;
    }

    public DynamicCertificateState getState() {
        return state;
    }

    public void setState(DynamicCertificateState state) {
        this.state = state;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public Order getPendingOrder() {
        return order;
    }

    public void setPendingOrder(Order order) {
        this.order = order;
    }

    public Challenge getPendingChallenge() {
        return challenge;
    }

    public void setPendingChallenge(Challenge challenge) {
        this.challenge = challenge;
    }

    public Certificate[] getChain() {
        return this.chain.clone();
    }

    public void setChain(List<X509Certificate> chain) {
        this.chain = chain.toArray(new Certificate[0]);
    }

    public boolean isExpired() {
        try {
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

    public KeyPair getKeyPair() {
        return keyPair;
    }

    void setKeyPair(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public CertificateData getData() throws GeneralSecurityException {
        return new CertificateData(this);
    }

    @Override
    public String toString() {
        return "DynamicCertificate{hostname=" + this.domain + ", state=" + state + ", available=" + available + '}';
    }

}
