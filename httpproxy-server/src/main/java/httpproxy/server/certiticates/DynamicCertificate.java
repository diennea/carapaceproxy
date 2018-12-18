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

import java.io.File;
import java.security.KeyPair;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import nettyhttpproxy.server.config.SSLCertificateConfiguration;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.challenge.Challenge;

/**
 *
 * SSL certificate issued via ACME
 * 
 * @author paolo.venturi
 */
public class DynamicCertificate {        

    public static enum DynamicCertificateState {
        WAITING, // certificato che deve essere generato/rinnovato
        VERIFYING, // richiesta verifica challenge a LE
        VERIFIED, // certificato verificato
        ORDERING, // ordine del certificato
        REQUEST_FAILED, // challenge/ordine falliti
        AVAILABLE, // salvato/disponibile/non scaduto
        EXPIRED     // certificato scaduto
    }

    private final String id;
    private final String hostname;
    private final boolean wildcard;
    private File sslCertificateFile;    

    private DynamicCertificateState state = DynamicCertificateState.WAITING;
    private boolean available = false;

    private Order order;
    private Challenge challenge;
    private List<X509Certificate> chain;
    private KeyPair keys;

    public DynamicCertificate(SSLCertificateConfiguration configuration) {
        this.id = configuration.getId();
        this.hostname = configuration.getHostname();
        this.sslCertificateFile = new File(configuration.getSslCertificateFile());
        this.wildcard = configuration.isWildcard();        
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public File getSslCertificateFile() {
        return sslCertificateFile;
    }

    public void setSslCertificateFile(File sslCertificateFile) {
        this.sslCertificateFile = sslCertificateFile;
    }

    public boolean isWildcard() {
        return wildcard;
    }

    public DynamicCertificateState getState() {
        return state;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setState(DynamicCertificateState state) {
        this.state = state;
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
        return this.chain.toArray(new Certificate[0]);
    }

    public void setChain(List<X509Certificate> chain) {
        this.chain = chain;
    }
    
    public boolean isExpired() {        
        try {
            if (chain != null && !chain.isEmpty()) {
               chain.get(0).checkValidity();
            } else {
                return true;
            }
        } catch (CertificateExpiredException | CertificateNotYetValidException ex) {
            return true;
        }
        return false;
    }

    public KeyPair getKeys() {
        return keys;
    }
    
    void setKeys(KeyPair keys) {
        this.keys = keys;
    }
    
    @Override
    public String toString() {
        return "DynamicCertificate{hostname=" + this.hostname + ", state=" + state + ", available=" + available + '}';
    }

}
