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

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;

public final class ConfigurationStoreUtils {

    private ConfigurationStoreUtils() {
    }

    public static int getInt(String key, int defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            return Integer.parseInt(properties.getProperty(key, defaultValue + ""));
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }

    public static long getLong(String key, long defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            return Long.parseLong(properties.getProperty(key, defaultValue + ""));
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }

    public static String getClassname(String key, String defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            Class.forName(property, true, Thread.currentThread().getContextClassLoader());
            return property;
        } catch (ClassNotFoundException err) {
            throw new ConfigurationNotValidException("Invalid class value '" + property + "' for parameter '" + key + "' : " + err);
        }
    }

    public static String base64EncodeKey(Key key) {
        byte[] data = key.getEncoded();
        if (key instanceof PrivateKey) { // Store Private Key
            data = new PKCS8EncodedKeySpec(data).getEncoded();
        } else if (key instanceof PublicKey) { // Store Public Key
            data = new X509EncodedKeySpec(data).getEncoded();
        }
        return Base64.getEncoder().encodeToString(data);
    }

    public static PrivateKey base64DecodePrivateKey(String key) throws GeneralSecurityException {
        Base64.Decoder dec = Base64.getDecoder();
        PKCS8EncodedKeySpec keySpecPKCS8 = new PKCS8EncodedKeySpec(dec.decode(key));
        return KeyFactory.getInstance("RSA").generatePrivate(keySpecPKCS8);
    }

    public static PublicKey base64DecodePublicKey(String key) throws GeneralSecurityException {
        Base64.Decoder dec = Base64.getDecoder();
        X509EncodedKeySpec keySpecX509 = new X509EncodedKeySpec(dec.decode(key));
        return KeyFactory.getInstance("RSA").generatePublic(keySpecX509);
    }

    public static String base64EncodeCertificateChain(Certificate[] chain, PrivateKey key) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(createKeystore(chain, key));
    }

    public static Certificate[] base64DecodeCertificateChain(String chain) throws GeneralSecurityException {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        byte[] data = Base64.getDecoder().decode(chain);
        return readChainFromKeystore(data);
    }

}
