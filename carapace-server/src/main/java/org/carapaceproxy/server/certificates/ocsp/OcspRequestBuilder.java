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

import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;

/**
 * This is a simplified version of BC's own {@link OCSPReqBuilder}.
 *
 * @see OCSPReqBuilder
 */
public class OcspRequestBuilder {

    private X509Certificate certificate;

    private X509Certificate issuer;

    public OcspRequestBuilder certificate(X509Certificate certificate) {
        this.certificate = certificate;
        return this;
    }

    public OcspRequestBuilder issuer(X509Certificate issuer) {
        this.issuer = issuer;
        return this;
    }

    public OCSPReq build() throws OCSPException, OperatorCreationException, CertificateEncodingException, IOException {
        // Get certId
        CertificateID certId = new CertificateID(
                (new BcDigestCalculatorProvider()).get(CertificateID.HASH_SHA1),
                new X509CertificateHolder(issuer.getEncoded()),
                certificate.getSerialNumber()
        );
        OCSPReqBuilder ocspRequestGenerator = new OCSPReqBuilder();
        ocspRequestGenerator.addRequest(certId);
        BigInteger nonce = BigInteger.valueOf(System.currentTimeMillis());
        Extension ext = new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false, new DEROctetString(nonce.toByteArray()));
        ocspRequestGenerator.setRequestExtensions(new Extensions(new Extension[]{ext}));

        return ocspRequestGenerator.build();
    }
}
