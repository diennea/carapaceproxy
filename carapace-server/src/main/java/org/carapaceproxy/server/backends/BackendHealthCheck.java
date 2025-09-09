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
package org.carapaceproxy.server.backends;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.ws.rs.core.UriBuilder;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.CertificatesUtils;
import org.carapaceproxy.utils.IOUtils;
import org.carapaceproxy.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The record models a single health check.
 * It includes information about when the backend was probed and what it responded.
 *
 * @param path         the probe path
 * @param startTs      when the request was done
 * @param endTs        when the answer was received
 * @param result       the probe result
 * @param httpResponse a string representation of the response header line
 * @param httpBody     a string representation of the body of the response
 */
public record BackendHealthCheck(
        String path,
        long startTs,
        long endTs,
        Result result,
        String httpResponse,
        String httpBody
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendHealthCheck.class);

    public static BackendHealthCheck check(final BackendConfiguration bconf, final int timeoutMillis) {
        final String scheme = StringUtils.isBlank(bconf.probeScheme()) ? "http" : bconf.probeScheme();
        final String probePath = bconf.probePath();
        final String caCertificatePath = bconf.caCertificatePath();
        final String caCertificatePassword = bconf.caCertificatePassword();
        if (probePath.isEmpty()) {
            final long now = System.currentTimeMillis();
            return new BackendHealthCheck(probePath, now, now, Result.SUCCESS, "OK", "MOCK OK");
        }
        final long now = System.currentTimeMillis();
        HttpURLConnection httpConnection = null;
        try {
            final URL url = UriBuilder.fromPath(probePath).scheme(scheme).host(bconf.host()).port(bconf.port()).build().toURL();
            URLConnection urlConnection = url.openConnection();
            urlConnection.setConnectTimeout(timeoutMillis);
            urlConnection.setReadTimeout(timeoutMillis);
            urlConnection.setUseCaches(false);

            if (!(urlConnection instanceof HttpURLConnection)) {
                throw new IllegalStateException("Only HttpURLConnection is supported");
            }
            httpConnection = (HttpURLConnection) urlConnection;

            // If using HTTPS and a custom CA is provided, configure SSL trust store
            if (httpConnection instanceof final HttpsURLConnection httpsConnection) {
                if (caCertificatePath != null && !caCertificatePath.isBlank()) {
                    try {
                        final File basePath = new File(".");
                        final KeyStore trustStore = CertificatesUtils.loadKeyStoreFromFile(caCertificatePath, caCertificatePassword != null ? caCertificatePassword : "", basePath);
                        if (trustStore != null) {
                            final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                            tmf.init(trustStore);
                            final SSLContext sslContext = SSLContext.getInstance("TLS");
                            sslContext.init(null, tmf.getTrustManagers(), null);
                            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                        }
                    } catch (GeneralSecurityException e) {
                        LOGGER.warn("Unable to load trust store", e);
                    }
                }
            }
            httpConnection.setRequestMethod("GET");
            httpConnection.setInstanceFollowRedirects(true);
            try (final InputStream is = httpConnection.getInputStream()) {
                final int httpCode = httpConnection.getResponseCode();
                return new BackendHealthCheck(
                        probePath,
                        now,
                        System.currentTimeMillis(),
                        httpCode >= 200 && httpCode <= 299 ? Result.SUCCESS : Result.FAILURE_STATUS,
                        httpCode + " " + Objects.toString(httpConnection.getResponseMessage(), ""),
                        IOUtils.toString(is, StandardCharsets.UTF_8)
                );
            }
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(ex);
        } catch (IOException | RuntimeException ex) {
            int httpCode = 0;
            String response = "";
            String httpErrorBody = "";
            if (httpConnection != null) {
                try {
                    httpCode = httpConnection.getResponseCode();
                } catch (IOException ignored) {
                    // Ignore
                }
                try {
                    response = httpCode + " " + Objects.toString(httpConnection.getResponseMessage(), "");
                } catch (IOException ignored) {
                    // Ignore
                }
                try {
                    httpErrorBody = IOUtils.toString(httpConnection.getErrorStream(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Ignore
                }
            }
            return new BackendHealthCheck(
                    probePath,
                    now,
                    System.currentTimeMillis(),
                    httpCode <= 0 ? Result.FAILURE_CONNECTION : Result.FAILURE_STATUS,
                    httpCode <= 0 ? ex.getMessage() : response,
                    httpErrorBody
            );
        }
    }

    public long responseTime() {
        return endTs - startTs;
    }

    public boolean ok() {
        return result == Result.SUCCESS;
    }

    public enum Result {
        SUCCESS, // 1
        FAILURE_CONNECTION, // 2
        FAILURE_STATUS // 3
    }
}
