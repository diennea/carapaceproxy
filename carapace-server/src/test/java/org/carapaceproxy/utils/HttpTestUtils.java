package org.carapaceproxy.utils;

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

import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS setup for tests, which run against self-signed certificates issued for names that do not resolve.
 * Everything here accepts any certificate and any hostname, so it must never leave the test sources.
 */
public class HttpTestUtils {

    private static final SSLSocketFactory SOCKET_FACTORY;

    private static final HostnameVerifier HOSTNAME_VERIFIER = (hostname, session) -> true;

    static {
        try {
            final SSLContext context = SSLContext.getInstance("SSL");
            context.init(null, new TrustManager[]{new AcceptAnyCertificate()}, null);
            SOCKET_FACTORY = context.getSocketFactory();
        } catch (final Exception e) {
            throw new IllegalStateException("cannot build the test SSL context", e);
        }
    }

    /**
     * @return a socket factory that trusts every certificate, for a client talking to a test listener
     */
    public static SSLSocketFactory getSocket_factory() {
        return SOCKET_FACTORY;
    }

    /**
     * Makes every {@link HttpsURLConnection} in this JVM trust any certificate and any hostname.
     * It changes global state and nothing restores it, so a test that calls it affects the ones after it.
     */
    public static void overrideJvmWideHttpsVerifier() {
        HttpsURLConnection.setDefaultHostnameVerifier(HOSTNAME_VERIFIER);
        HttpsURLConnection.setDefaultSSLSocketFactory(SOCKET_FACTORY);
    }

    private static class AcceptAnyCertificate implements X509TrustManager {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
