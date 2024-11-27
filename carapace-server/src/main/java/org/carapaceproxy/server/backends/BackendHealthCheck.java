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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.ws.rs.core.UriBuilder;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.IOUtils;

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

    public static BackendHealthCheck check(final BackendConfiguration bconf, final int timeoutMillis) {
        return check(bconf.host(), bconf.port(), bconf.probePath(), timeoutMillis);
    }

    public static BackendHealthCheck check(final String host, final int port, final String path, final int timeoutMillis) {
        if (path.isEmpty()) {
            long now = System.currentTimeMillis();
            return new BackendHealthCheck(path, now, now, Result.SUCCESS, "OK", "MOCK OK");
        }
        final long startTs = System.currentTimeMillis();
        HttpURLConnection httpConn = null;
        try {
            final URL url = UriBuilder.fromPath(path).scheme("http").host(host).port(port).build().toURL();
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setUseCaches(false);

            if (!(conn instanceof HttpURLConnection)) {
                throw new IllegalStateException("Only HttpURLConnection is supported");
            }
            httpConn = (HttpURLConnection) conn;
            httpConn.setRequestMethod("GET");
            httpConn.setInstanceFollowRedirects(true);

            try (InputStream is = httpConn.getInputStream()) {
                int httpCode = httpConn.getResponseCode();
                String httpResponse = httpCode + " " + Objects.toString(httpConn.getResponseMessage(), "");
                String httpBody = IOUtils.toString(is, StandardCharsets.UTF_8);
                return new BackendHealthCheck(
                        path,
                        startTs,
                        System.currentTimeMillis(),
                        httpCode >= 200 && httpCode <= 299 ? Result.SUCCESS : Result.FAILURE_STATUS,
                        httpResponse,
                        httpBody
                );
            }

        } catch (MalformedURLException ex) {
            throw new RuntimeException(ex);

        } catch (IOException | RuntimeException ex) {
            Result result = Result.FAILURE_STATUS;
            int httpCode = 0;
            String httpResponse = "";
            String httpErrorBody = "";

            if (httpConn != null) {
                try {
                    httpCode = httpConn.getResponseCode();
                    httpResponse = httpCode + " " + Objects.toString(httpConn.getResponseMessage(), "");
                } catch (IOException ex2) {
                    // Ignore
                }

                try {
                    httpErrorBody = IOUtils.toString(httpConn.getErrorStream(), StandardCharsets.UTF_8);
                } catch (IOException ex2) {
                    // Ignore
                }
            }

            if (httpCode <= 0) {
                result = Result.FAILURE_CONNECTION;
                httpResponse = ex.getMessage();
            }
            return new BackendHealthCheck(
                    path,
                    startTs,
                    System.currentTimeMillis(),
                    result,
                    httpResponse,
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
