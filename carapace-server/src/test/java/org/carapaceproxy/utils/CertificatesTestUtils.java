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
package org.carapaceproxy.utils;

import com.google.common.annotations.VisibleForTesting;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Random;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import static org.carapaceproxy.server.certiticates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import org.carapaceproxy.utils.RawHttpClient.BasicAuthCredentials;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import org.shredzone.acme4j.util.KeyPairUtils;
import static org.shredzone.acme4j.util.KeyPairUtils.createKeyPair;

/**
 *
 * @author paolo
 */
public class CertificatesTestUtils {

    public static Certificate[] generateSampleChain(KeyPair endUserKeypair, boolean expired) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        // Create self signed Root CA certificate
        KeyPair rootCAKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=rootCA"), // issuer authority
                BigInteger.valueOf(new Random().nextInt()), //serial number of certificate
                new Date(), // start of validity
                new Date(), //end of certificate validity
                new X500Name("CN=rootCA"), // subject name of certificate
                rootCAKeyPair.getPublic()
        ); // public key of certificate

        // Key usage restrictions
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(true));

        // Root certificate
        X509Certificate rootCA = new JcaX509CertificateConverter().getCertificate(builder
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").
                        build(rootCAKeyPair.getPrivate()))); // private key of signing authority , here it is self signed

        // Create Intermediate CA cert signed by Root CA
        KeyPair intermedCAKeyPair = createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        builder = new JcaX509v3CertificateBuilder(
                rootCA, // here rootCA is issuer authority
                BigInteger.valueOf(new Random().nextInt()),
                new Date(),
                new Date(),
                new X500Name("CN=IntermedCA"), intermedCAKeyPair.getPublic());

        // Key usage restrictions
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(true));

        // Intermediate certificate
        X509Certificate intermediateCA = new JcaX509CertificateConverter().getCertificate(builder
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").
                        build(rootCAKeyPair.getPrivate())));// private key of signing authority , here it is signed by rootCA

        //create end user cert signed by Intermediate CA
        int offset = 1000 * 60 * 60 * 24; // yesterday/tomorrow
        Date expiringDate = new Date(System.currentTimeMillis() + (expired ? - offset : + offset));
        builder = new JcaX509v3CertificateBuilder(
                intermediateCA, //here intermedCA is issuer authority
                BigInteger.valueOf(new Random().nextInt()),
                new Date(System.currentTimeMillis() - offset),
                expiringDate,
                new X500Name("CN=endUserCert"), endUserKeypair.getPublic());

        // Key usage restrictions
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        // End-user certificate
        X509Certificate endUserCert = new JcaX509CertificateConverter().getCertificate(builder
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").
                        build(intermedCAKeyPair.getPrivate())));// private key of signing authority , here it is signed by intermedCA

        return new X509Certificate [] {
            endUserCert,
            intermediateCA,
            rootCA,
        };
    }

    public static byte[] generateSampleChainData() throws Exception {
        KeyPair endUserKeyPair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        Certificate[] originalChain = generateSampleChain(endUserKeyPair, false);
        return createKeystore(originalChain, endUserKeyPair.getPrivate());
    }

    public static HttpResponse uploadCertificate(String domain, byte[] data, RawHttpClient client, BasicAuthCredentials credentials) throws Exception {

        ByteArrayOutputStream oo = new ByteArrayOutputStream();
        oo.write(("POST /api/certificates/" + domain + "/upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Authorization: Basic " + credentials.toBase64() + "\r\n"
                + "\r\n").getBytes("ASCII"));
        oo.write(data);

        return client.executeRequest(new ByteArrayInputStream(oo.toByteArray()));
    }
}
