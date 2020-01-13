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
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.BERTags;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.x509.extension.X509ExtensionUtil;

import io.netty.util.CharsetUtil;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OcspUtils {

    /**
     * The OID for OCSP responder URLs.
     *
     * http://www.alvestrand.no/objectid/1.3.6.1.5.5.7.48.1.html
     */
    private static final ASN1ObjectIdentifier OCSP_RESPONDER_OID =
            new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.1").intern();

    private static final String OCSP_REQUEST_TYPE = "application/ocsp-request";
    private static final String OCSP_RESPONSE_TYPE = "application/ocsp-response";

    private static final Logger LOG = Logger.getLogger(OcspStaplingManager.class.getName());

    private OcspUtils() {
    }

    /**
     * Returns the OCSP responder {@link URI} or {@code null} if it doesn't have one.
     */
    public static URI ocspUri(X509Certificate certificate) throws IOException {
        byte[] value = certificate.getExtensionValue(Extension.authorityInfoAccess.getId());
        if (value == null) {
            return null;
        }

        ASN1Primitive authorityInfoAccess = X509ExtensionUtil.fromExtensionValue(value);
        if (!(authorityInfoAccess instanceof DLSequence)) {
            return null;
        }

        DLSequence aiaSequence = (DLSequence) authorityInfoAccess;
        DERTaggedObject taggedObject = findObject(aiaSequence, OCSP_RESPONDER_OID, DERTaggedObject.class);
        if (taggedObject == null) {
            return null;
        }

        if (taggedObject.getTagNo() != BERTags.OBJECT_IDENTIFIER) {
            return null;
        }

        byte[] encoded = taggedObject.getEncoded();
        int length = (int) encoded[1] & 0xFF;
        String uri = new String(encoded, 2, length, CharsetUtil.UTF_8);
        return URI.create(uri);
    }

    private static <T> T findObject(DLSequence sequence, ASN1ObjectIdentifier oid, Class<T> type) {
        for (ASN1Encodable element : sequence) {
            if (!(element instanceof DLSequence)) {
                continue;
            }

            DLSequence subSequence = (DLSequence) element;
            if (subSequence.size() != 2) {
                continue;
            }

            ASN1Encodable key = subSequence.getObjectAt(0);
            ASN1Encodable value = subSequence.getObjectAt(1);

            if (key.equals(oid) && type.isInstance(value)) {
                return type.cast(value);
            }
        }

        return null;
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
}
