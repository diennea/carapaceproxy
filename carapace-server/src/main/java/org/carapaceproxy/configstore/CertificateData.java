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
package org.carapaceproxy.configstore;

import java.net.URL;
import java.util.Date;
import java.util.Objects;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
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
    private volatile DynamicCertificateState state;
    private String pendingOrderLocation;
    private String pendingChallengeData;

    // Data available at run-time only
    private boolean wildcard;
    private boolean manual;
    private int daysBeforeRenewal;
    private Date expiringDate;
    private String serialNumber; // hex
    private byte[] keystoreData; // decoded chain

    public CertificateData(String domain, String privateKey, String chain, DynamicCertificateState state,
                           String orderLocation, String challengeData) {
        this.domain = domain;
        this.privateKey = privateKey;
        this.chain = chain;
        this.state = state;
        this.pendingOrderLocation = orderLocation;
        this.pendingChallengeData = challengeData;
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

    public DynamicCertificateState getState() {
        return state;
    }

    public String getPendingOrderLocation() {
        return pendingOrderLocation;
    }

    public String getPendingChallengeData() {
        return pendingChallengeData;
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

    public void setState(DynamicCertificateState state) {
        this.state = state;
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

    public boolean isWildcard() {
        return wildcard;
    }

    public void setWildcard(boolean wildcard) {
        this.wildcard = wildcard;
    }

    public boolean isManual() {
        return manual;
    }

    public void setManual(boolean manual) {
        this.manual = manual;
    }

    public int getDaysBeforeRenewal() {
        return daysBeforeRenewal;
    }

    public void setDaysBeforeRenewal(int daysBeforeRenewal) {
        this.daysBeforeRenewal = daysBeforeRenewal;
    }

    public Date getExpiringDate() {
        return expiringDate;
    }

    public void setExpiringDate(Date expiringDate) {
        this.expiringDate = expiringDate;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public byte[] getKeystoreData() {
        return keystoreData;
    }

    public void setKeystoreData(byte[] keystoreData) {
        this.keystoreData = keystoreData;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 89 * hash + Objects.hashCode(this.domain);
        hash = 89 * hash + Objects.hashCode(this.privateKey);
        hash = 89 * hash + Objects.hashCode(this.chain);
        hash = 89 * hash + Objects.hashCode(this.state);
        hash = 89 * hash + Objects.hashCode(this.pendingOrderLocation);
        hash = 89 * hash + Objects.hashCode(this.pendingChallengeData);
        hash = 89 * hash + (this.manual ? 1 : 0);
        hash = 89 * hash + this.daysBeforeRenewal;
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
        if (this.manual != other.manual) {
            return false;
        }
        if (this.daysBeforeRenewal != other.daysBeforeRenewal) {
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
        if (!Objects.equals(this.pendingOrderLocation, other.pendingOrderLocation)) {
            return false;
        }
        if (!Objects.equals(this.pendingChallengeData, other.pendingChallengeData)) {
            return false;
        }
        if (this.state != other.state) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "CertificateData{" + "domain=" + domain + ", state=" + state + ", manual=" + manual + '}';
    }

}
