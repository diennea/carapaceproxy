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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.shredzone.acme4j.toolbox.JSON;

/**
 *
 * Bean for ACME Certificates ({@link DynamicCertificate}) data stored in database.
 *
 * @author paolo.venturi
 */
@Data
public class CertificateData {

    private String domain; // hostname or *.hostname
    @EqualsAndHashCode.Exclude
    private Set<String> subjectAlternativeNames;
    @ToString.Exclude
    private String chain; // base64 encoded string of the KeyStore.
    private volatile DynamicCertificateState state;
    private URL pendingOrderLocation;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Map<String, JSON> pendingChallengesData = new HashMap<>();
    private boolean manual;
    private int daysBeforeRenewal;
    private Date expiringDate;
    private String serialNumber; // hex
    @ToString.Exclude
    private byte[] keystoreData; // decoded chain

    public CertificateData(String domain,
                           String chain,
                           DynamicCertificateState state) {
        this(domain, null, chain, state, null, null);
    }
    public CertificateData(String domain,
                           Set<String> subjectAlternativeNames,
                           String chain,
                           DynamicCertificateState state,
                           URL orderLocation,
                           Map<String, JSON> challengesData) {
        this.domain = Objects.requireNonNull(domain);
        this.subjectAlternativeNames = subjectAlternativeNames;
        this.chain = chain;
        this.state = state;
        this.pendingOrderLocation = orderLocation;
        this.pendingChallengesData = challengesData;
    }

    public Collection<String> getNames() {
        return new ArrayList<>() {{
            add(domain);
            if (subjectAlternativeNames != null) {
                addAll(subjectAlternativeNames);
            }
        }};
    }

}
