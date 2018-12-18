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

import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_CERT_ALIAS;
import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_FORMAT;
import static httpproxy.server.certiticates.DynamicCertificateStore.KEYSTORE_PW;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author paolo.venturi
 */
public class DynamicCertificateStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final String encodedCertContent = 
              "MIIGBDCCBOygAwIBAgITAPpWVSiDTlLB1zVsAI/HIL50/TANBgkqhkiG9w0BAQsF"
            + "ADAiMSAwHgYDVQQDDBdGYWtlIExFIEludGVybWVkaWF0ZSBYMTAeFw0xODEwMjYx"
            + "NDA5NTNaFw0xOTAxMjQxNDA5NTNaMCcxJTAjBgNVBAMTHHNpdGUyLXFhcGF0Y2gu"
            + "aW5mb3JtYXRpY2EuaXQwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCr"
            + "X6P8GhnMrnK+7KWCxPbz27isIn09nhLZv9tv/k5Kwwc5t4vCx0DYW9Sjupnsl+9z"
            + "muYFtmNhLR97EVq3pMdOinBk+PuYB8ooAIow8ynkxl0f2z0JsRxZ/F+Jxnd56f5s"
            + "BDhR6PgYa8eWvmYtlyd2GLa3MpSGRNA0Rn2gAWjEW5PIu2oHGpaAHtKcoORBJIO6"
            + "oUJBVfRIx5eP6qhxkpHimTBp/3GnmrIRJDjCtVA5Djw4KYzNLKko1BlGY74cUzpl"
            + "Mh6LUGhOqRTCpjI3NDijYCLmgMLzVEQLnv9OkdJwqsop5B/EIkd/Imj4jB7iutyu"
            + "kigvzMwv64yCQc+NC4f3AgMBAAGjggMsMIIDKDAOBgNVHQ8BAf8EBAMCBaAwHQYD"
            + "VR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMAwGA1UdEwEB/wQCMAAwHQYDVR0O"
            + "BBYEFL0LJhVo4GYcR+c8vjvQV9FH1IF6MB8GA1UdIwQYMBaAFMDMA0a5WCDMXHJw"
            + "8+EuyyCm9Wg6MHcGCCsGAQUFBwEBBGswaTAyBggrBgEFBQcwAYYmaHR0cDovL29j"
            + "c3Auc3RnLWludC14MS5sZXRzZW5jcnlwdC5vcmcwMwYIKwYBBQUHMAKGJ2h0dHA6"
            + "Ly9jZXJ0LnN0Zy1pbnQteDEubGV0c2VuY3J5cHQub3JnLzAnBgNVHREEIDAeghxz"
            + "aXRlMi1xYXBhdGNoLmluZm9ybWF0aWNhLml0MIH+BgNVHSAEgfYwgfMwCAYGZ4EM"
            + "AQIBMIHmBgsrBgEEAYLfEwEBATCB1jAmBggrBgEFBQcCARYaaHR0cDovL2Nwcy5s"
            + "ZXRzZW5jcnlwdC5vcmcwgasGCCsGAQUFBwICMIGeDIGbVGhpcyBDZXJ0aWZpY2F0"
            + "ZSBtYXkgb25seSBiZSByZWxpZWQgdXBvbiBieSBSZWx5aW5nIFBhcnRpZXMgYW5k"
            + "IG9ubHkgaW4gYWNjb3JkYW5jZSB3aXRoIHRoZSBDZXJ0aWZpY2F0ZSBQb2xpY3kg"
            + "Zm91bmQgYXQgaHR0cHM6Ly9sZXRzZW5jcnlwdC5vcmcvcmVwb3NpdG9yeS8wggEE"
            + "BgorBgEEAdZ5AgQCBIH1BIHyAPAAdgDdmTT8peckgMlWaH2BNJkISbJJ97Vp2Me8"
            + "qz9cwfNuZAAAAWaw7K4yAAAEAwBHMEUCIH2XQVV0W0IXBoZ8+bIH31jf4Mob05M7"
            + "gcHHWChnDnYqAiEAuonRnxa282VukPTJGwzuaAIdzpjv2mzhgGsGuYGjxd4AdgAW"
            + "6GnB0ZXq18P4lxrj8HYB94zhtp0xqFIYtoN/MagVCAAAAWaw7LAmAAAEAwBHMEUC"
            + "IQCyfByfYkMfuctVSrithxLq/YK6QtGHVsaJ/6Wh/RlcfgIgcTTrVUl8EX873PAr"
            + "76wq9LWQoLjjZ0SSb1460fmOW74wDQYJKoZIhvcNAQELBQADggEBANbICKt7sxKZ"
            + "ZkDnAzI16DROzl4GkRUFhx1+yD4BgSuoFoFDAxQsu2c7alK/7kRmtfYkNPqWJ61u"
            + "5m5/Utwqdi2q74cHZ8ciFoZibhTHyU8uHLhd+Vt0Uf/skz5JvK2pbLU8Patgs72e"
            + "HxcQmBUzn65u5KJFilrPd/4rJkfuF3o1vH9ONDTt+amj6Qu1bE+/0J0LLspxJVpZ"
            + "WGSBdYBJ4rdtRAiHE9fpvtuxI5QZmczDaQJBcwYYSvC/G492IMEPVCMCuWHZWZQc"
            + "iIkfQgYALqFu4WF93MYUtRGmQA0SjDIB2vqu+g+hZCQ09d10RnpnlbPgZLHD8rZJ"
            + "BESbPX6Doc0=";

    @Test
    public void testCertificateStoring() throws ConfigurationNotValidException, DynamicCertificateStore.DynamicCertificateStoreException, IOException, CertificateException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        File basePath = folder.getRoot();
        DynamicCertificateStore store = new DynamicCertificateStore(basePath);

        Properties props = new Properties();
        String d2 = "site2-qapatch.informatica.it";
        props.setProperty("certificate.0.hostname", d2);
        props.setProperty("certificate.0.isdynamic", "true");
        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
        conf.configure(new PropertiesConfigurationStore(props));
        DynamicCertificate certificate = new DynamicCertificate(conf.getCertificates().get(d2));

        byte[] decoded = Base64.getDecoder().decode(encodedCertContent);
        X509Certificate cert;
        try (InputStream is = new ByteArrayInputStream(decoded)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
        certificate.setChain(Arrays.asList(cert));
        certificate.setKeys(store.loadOrCreateKeyForDomain(d2));

        store.saveCertificate(certificate);        
        assertTrue(Files.exists(certificate.getSslCertificateFile().toPath()));
        
        // Verifica della chiave salvata e del certificato
        KeyStore ks = KeyStore.getInstance(KEYSTORE_FORMAT);
        try (InputStream is = new FileInputStream(certificate.getSslCertificateFile())) {
            ks.load(is, KEYSTORE_PW);
        }

        Key key = ks.getKey(KEYSTORE_CERT_ALIAS, KEYSTORE_PW);
        assertNotNull(key);
        assertTrue(Arrays.equals(certificate.getKeys().getPrivate().getEncoded(), key.getEncoded()));
        
        X509Certificate savedCert = (X509Certificate) ks.getCertificate(KEYSTORE_CERT_ALIAS);
        assertNotNull(savedCert);        
                
        assertTrue(savedCert.toString().contains("Subject: CN=site2-qapatch.informatica.it"));
        assertTrue(savedCert.toString().contains("accessMethod: caIssuers\n" +"   accessLocation: URIName: http://cert.stg-int-x1.letsencrypt.org/"));
    }

}
