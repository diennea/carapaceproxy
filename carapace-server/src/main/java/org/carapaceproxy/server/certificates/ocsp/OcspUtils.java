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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;

public final class OcspUtils {

    /**
     * The OID for OCSP responder URLs.
     *
     * http://www.alvestrand.no/objectid/1.3.6.1.5.5.7.48.1.html
     */
    private static final String OCSP_REQUEST_TYPE = "application/ocsp-request";
    private static final String OCSP_RESPONSE_TYPE = "application/ocsp-response";

    private static final Logger LOG = Logger.getLogger(OcspStaplingManager.class.getName());

    private OcspUtils() {
    }

    /**
     * Returns the OCSP responder {@link URI} or {@code null} if it doesn't have one.
     *
     * @param certificate
     * @return
     * @throws java.io.IOException
     */
    public static URI getOcspUri(X509Certificate certificate) throws IOException {
        ASN1Primitive obj = getExtensionValue(certificate, Extension.authorityInfoAccess.getId());
        if (obj == null) {
            return null;
        }
        AuthorityInformationAccess authorityInformationAccess = AuthorityInformationAccess.getInstance(obj);
        AccessDescription[] accessDescriptions = authorityInformationAccess.getAccessDescriptions();
        for (AccessDescription accessDescription : accessDescriptions) {
            boolean correctAccessMethod = accessDescription.getAccessMethod().equals(X509ObjectIdentifiers.ocspAccessMethod);
            if (!correctAccessMethod) {
                continue;
            }
            GeneralName name = accessDescription.getAccessLocation();
            if (name.getTagNo() != GeneralName.uniformResourceIdentifier) {
                continue;
            }
            byte[] encoded = ((ASN1TaggedObject) name.toASN1Primitive()).getEncoded();
            int length = (int) encoded[1] & 0xFF;
            String uri = new String(encoded, 2, length, CharsetUtil.UTF_8);
            return URI.create(uri);
        }

        return null;
    }

    private static ASN1Primitive getExtensionValue(X509Certificate certificate, String oid) throws IOException {
        byte[] bytes = certificate.getExtensionValue(oid);
        if (bytes == null) {
            return null;
        }
        ASN1InputStream aIn = new ASN1InputStream(new ByteArrayInputStream(bytes));
        ASN1OctetString octs = (ASN1OctetString) aIn.readObject();
        aIn = new ASN1InputStream(new ByteArrayInputStream(octs.getOctets()));
        return aIn.readObject();
    }

    public static OCSPResp request(String dn, URI uri, OCSPReq request, long timeout, TimeUnit unit) throws IOException {
        byte[] encoded = request.getEncoded();
        URL url = uri.toURL();
        LOG.log(Level.INFO, "Performing OCSP request for {0} to {1}", new Object[]{dn, uri});
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout((int) unit.toMillis(timeout));
            connection.setReadTimeout((int) unit.toMillis(timeout));
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("host", uri.getHost());
            connection.setRequestProperty("content-type", OCSP_REQUEST_TYPE);
            connection.setRequestProperty("accept", OCSP_RESPONSE_TYPE);
            connection.setRequestProperty("content-length", String.valueOf(encoded.length));

            try (OutputStream out = connection.getOutputStream()) {
                out.write(encoded);
                out.flush();
                try (InputStream in = connection.getInputStream()) {
                    int code = connection.getResponseCode();
                    if (code != HttpsURLConnection.HTTP_OK) {
                        throw new IOException("Unexpected status-code=" + code);
                    }

                    String contentType = connection.getContentType();
                    if (!contentType.equalsIgnoreCase(OCSP_RESPONSE_TYPE)) {
                        throw new IOException("Unexpected content-type=" + contentType);
                    }

                    return new OCSPResp(in.readAllBytes());
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    public static X509Certificate getIssuerCertificate(Certificate[] certificates, Map<String, X509Certificate> trustedCA) {
        if(certificates.length > 1) {
            return (X509Certificate) certificates[1];
        } else if (certificates.length == 1) {
            final X509Certificate cert = (X509Certificate) certificates[0];
            X500Principal issuerPrincipal = cert.getIssuerX500Principal();
            return trustedCA.get(issuerPrincipal.getName());
        } else {
            return null;
        }
    }
}
