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
package org.carapaceproxy.configstore;

import java.net.URL;
import java.util.Objects;
import org.carapaceproxy.server.certiticates.DynamicCertificateState;
import org.shredzone.acme4j.toolbox.JSON;

/**
 *
 * Bean for ACME Certificates ({@link DynamicCertificate}) data stored in database.
 *
 * @author paolo.venturi
 */
public class CertificateData {

    private String domain;
    private String privateKey; // base64 encoded string.
    private String chain; // base64 encoded string of the KeyStore.
    private String state;
    private String pendingOrderLocation;
    private String pendingChallengeData;
    private boolean available;

    public CertificateData(String domain, String privateKey, String chain, String state,
            String orderLocation, String challengeData, boolean available) {
        this.domain = domain;
        this.privateKey = privateKey;
        this.chain = chain;
        this.state = state;
        this.pendingOrderLocation = orderLocation;
        this.pendingChallengeData = challengeData;
        this.available = available;
    }

    public String getDomain() {
        return domain;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getChain() {
        return chain;
    }

    public String getState() {
        return state;
    }

    public String getPendingOrderLocation() {
        return pendingOrderLocation;
    }

    public String getPendingChallengeData() {
        return pendingChallengeData;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setPrivateKey(String keypair) {
        this.privateKey = keypair;
    }

    public void setChain(String chain) {
        this.chain = chain;
    }   

    public void setState(String state) {
        this.state = state;
    }

    public void setState(DynamicCertificateState state) {
        this.state = state.name();
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setPendingOrderLocation(String orderLocation) {
        this.pendingOrderLocation = orderLocation;
    }

    public void setPendingOrderLocation(URL orderLocation) {
        this.pendingOrderLocation = orderLocation.toString();
    }

    public void setPendingChallengeData(String challengeData) {
        this.pendingChallengeData = challengeData;
    }

    public void setPendingChallengeData(JSON challengeData) {
        this.pendingChallengeData = challengeData.toString();
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 59 * hash + Objects.hashCode(this.domain);
        hash = 59 * hash + Objects.hashCode(this.privateKey);
        hash = 59 * hash + Objects.hashCode(this.chain);
        hash = 59 * hash + Objects.hashCode(this.state);
        hash = 59 * hash + Objects.hashCode(this.pendingOrderLocation);
        hash = 59 * hash + Objects.hashCode(this.pendingChallengeData);
        hash = 59 * hash + (this.available ? 1 : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final CertificateData other = (CertificateData) obj;
        if (this.available != other.available) {
            return false;
        }
        if (!Objects.equals(this.domain, other.domain)) {
            return false;
        }
        if (!Objects.equals(this.privateKey, other.privateKey)) {
            return false;
        }
        if (!Objects.equals(this.chain, other.chain)) {
            return false;
        }
        if (!Objects.equals(this.state, other.state)) {
            return false;
        }
        if (!Objects.equals(this.pendingOrderLocation, other.pendingOrderLocation)) {
            return false;
        }
        if (!Objects.equals(this.pendingChallengeData, other.pendingChallengeData)) {
            return false;
        }
        return true;
    }

}
