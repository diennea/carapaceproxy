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

import org.carapaceproxy.server.certiticates.DynamicCertificate;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Objects;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64EncodeKey;

/**
 *
 * Bean for ACME Certificates ({@link DynamicCertificate}) data stored in
 * database.
 *
 * @author paolo.venturi
 */
public class CertificateData {

    private String domain;
    private String privateKey; // base64 encoded string.
    private String chain; // base64 encoded string of the KeyStore.
    private boolean available;

    public CertificateData(String domain, String privateKey, String chain, boolean available) {
        this.domain = domain;
        this.privateKey = privateKey;
        this.chain = chain;
        this.available = available;
    }

    public CertificateData(DynamicCertificate certificate) throws GeneralSecurityException {
        this.domain = certificate.getDomain();
        this.available = certificate.isAvailable();
        PrivateKey _privateKey = certificate.getKeyPair().getPrivate();
        this.privateKey = base64EncodeKey(_privateKey);
        this.chain = base64EncodeCertificateChain(certificate.getChain(), _privateKey);
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

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public int hashCode() {
        int hash = 3;
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
        return true;
    }

}
