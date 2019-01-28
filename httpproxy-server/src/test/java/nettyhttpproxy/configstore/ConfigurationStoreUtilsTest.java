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
package nettyhttpproxy.configstore;

import static httpproxy.server.certiticates.DynamicCertificatesManager.DEFAULT_KEYPAIRS_SIZE;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;
import static nettyhttpproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import static nettyhttpproxy.configstore.ConfigurationStoreUtils.base64DecodePrivateKey;
import static nettyhttpproxy.configstore.ConfigurationStoreUtils.base64DecodePublicKey;
import static nettyhttpproxy.configstore.ConfigurationStoreUtils.base64EncodeCertificateChain;
import static nettyhttpproxy.configstore.ConfigurationStoreUtils.base64EncodeKey;
import static nettyhttpproxy.utils.TestUtils.assertEqualsKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.shredzone.acme4j.util.KeyPairUtils;
import static org.shredzone.acme4j.util.KeyPairUtils.createKeyPair;

/**
 *
 * @author paolo.venturi
 */
public class ConfigurationStoreUtilsTest {

    private PrivateKey endUserPrivateKey;

    @Test
    public void testBase64EncodeDecodeKeys() throws Exception {
        KeyPair pair = KeyPairUtils.createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();

        String _privateKey = base64EncodeKey(privateKey);
        System.out.println("Size of privateKey: " + _privateKey.length());
        String _publicKey = base64EncodeKey(publicKey);
        System.out.println("Size of publicKey: " + _publicKey.length());

        assertEqualsKey(privateKey, base64DecodePrivateKey(_privateKey));
        assertEqualsKey(publicKey, base64DecodePublicKey(_publicKey));
    }

    @Test
    public void testBase64EncodeEncodeCertificateChain() throws Exception {
        Certificate[] originalChain = generateSampleChain();
        String encodedChain = base64EncodeCertificateChain(originalChain, endUserPrivateKey);
        Certificate[] decodedChain = base64DecodeCertificateChain(encodedChain);

        assertNotNull(decodedChain);
        assertEquals(originalChain.length, decodedChain.length);
        for (int i = 0; i < decodedChain.length; i++) {
            Certificate decodedCert = decodedChain[i];
            assertNotNull(decodedCert);
            assertTrue(Arrays.equals(decodedCert.getEncoded(), originalChain[i].getEncoded()));
        }
    }

    private Certificate[] generateSampleChain() throws Exception {
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
        KeyPair endUserCertKeyPair = createKeyPair(DEFAULT_KEYPAIRS_SIZE);
        endUserPrivateKey = endUserCertKeyPair.getPrivate();
        builder = new JcaX509v3CertificateBuilder(
                intermediateCA, //here intermedCA is issuer authority
                BigInteger.valueOf(new Random().nextInt()),
                new Date(),
                new Date(),
                new X500Name("CN=endUserCert"), endUserCertKeyPair.getPublic());

        // Key usage restrictions
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        // End-user certificate
        X509Certificate endUserCert = new JcaX509CertificateConverter().getCertificate(builder
                .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").
                        build(intermedCAKeyPair.getPrivate())));// private key of signing authority , here it is signed by intermedCA

        X509Certificate[] chain = new X509Certificate[3];
        chain[0] = endUserCert;
        chain[1] = intermediateCA;
        chain[2] = rootCA;

        return chain;
    }

}
