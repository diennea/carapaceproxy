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
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.codec.binary.Base64;

/**
 * HTTPs utilities
 *
 * @author enrico.olivelli
 */
public class HttpTestUtils {

    private static SSLSocketFactory socket_factory = null;

    static {
        // SSL initialization to avoid certificates validity
        X509TrustManager xtm = new MyTrustManager();
        TrustManager mytm[] = {xtm};
        try {
            SSLContext ctx = SSLContext.getInstance("SSL");
            ctx.init(null, mytm, null);
            socket_factory = ctx.getSocketFactory();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
    private static HostnameVerifier hostname_verifier = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            System.out.println("[SSLCLIENT] verify " + hostname + ", session " + session);
            return true;
        }
    };

    public static SSLSocketFactory getSocket_factory() {
        return socket_factory;
    }

    public static void readFullContent(URLConnection con) throws IOException {
        try (InputStream in = con.getInputStream()) {
            int b = in.read();
            while (b != -1) {
                b = in.read();
            }
        }
    }

    public static class ResourceInfos {

        public final long length;
        public final String contenttype;
        public final int responseCode;
        public final String response;
        public final String location;
        public final URL url;

        private ResourceInfos(URL url, long length, String contenttype, int responseCode, String response, String location) {
            this.url = url;
            this.length = length;
            this.contenttype = contenttype;
            this.responseCode = responseCode;
            this.response = response;
            this.location = location;
        }

        @Override
        public String toString() {
            return "ResourceInfos{" + "length=" + length + ", contenttype=" + contenttype + ", responseCode=" + responseCode + ", response=" + response + ", location=" + location + '}';
        }

    }

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static long copyStreams(InputStream input, OutputStream output) throws IOException {
        long count = 0;
        int n = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        while (-1 != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    public static ResourceInfos downloadFromUrl(URL url, OutputStream out, Map<String, Object> options) throws IOException {

        // Options gathering
        if (options == null) {
            options = Collections.emptyMap();
        }
        boolean return_errors = false;
        if (options.containsKey("return_errors")) {
            return_errors = Boolean.parseBoolean(options.get("return_errors") + "");
        }

        int read_timeout = 0;
        if (options.containsKey("read_timeout")) {
            read_timeout = Integer.parseInt(options.get("read_timeout") + "");
        }
        if (read_timeout <= 0) {
            read_timeout = 60000;
        }
        int connect_timeout = 0;
        if (options.containsKey("connect_timeout")) {
            connect_timeout = Integer.parseInt(options.get("connect_timeout") + "");
        }
        if (connect_timeout <= 0) {
            connect_timeout = 60000;
        }

        String userAgent = null;
        if (options.containsKey("user_agent")) {
            userAgent = options.get("user_agent") + "";
        }

        Proxy proxy = null;
        if (options.containsKey("proxy")) {
            proxy = (Proxy) options.get("proxy");
        }

        boolean instance_follow_redirects = true;
        if (options.containsKey("instance_follow_redirects")) {
            instance_follow_redirects = Boolean.parseBoolean(options.get("instance_follow_redirects") + "");
        }
        boolean disable_https_validation = false;
        if (options.containsKey("disable_https_validation")) {
            disable_https_validation = Boolean.parseBoolean(options.get("disable_https_validation") + "");
        }
        boolean verify_ssl_hostnames = false;
        if (options.containsKey("verify_ssl_hostnames")) {
            verify_ssl_hostnames = Boolean.parseBoolean(options.get("verify_ssl_hostnames") + "");
        }
        boolean retry_on_ssl_error_and_custom_verifier = false;
        if (options.containsKey("retry_on_ssl_error_and_custom_verifier")) {
            retry_on_ssl_error_and_custom_verifier = Boolean.parseBoolean(options.get("retry_on_ssl_error_and_custom_verifier") + "");
        }

        Map<String, Object> headers = null;
        if (options.containsKey("headers")) {
            headers = (Map<String, Object>) options.get("headers");
        }

        // Create connection
        URLConnection con;
        if (proxy != null) {
            con = url.openConnection(proxy);
        } else {
            con = url.openConnection();
        }

        if (disable_https_validation || verify_ssl_hostnames) {
            HttpTestUtils.tweakConnection(con, disable_https_validation, verify_ssl_hostnames);
        }

        con.setUseCaches(false);
        con.setReadTimeout(read_timeout);
        con.setConnectTimeout(connect_timeout);
        HttpURLConnection httpCon = null;
        if (con instanceof HttpURLConnection) {
            httpCon = (HttpURLConnection) con;
            httpCon.setInstanceFollowRedirects(false);
        }

        if (userAgent != null) {
            con.setRequestProperty("User-Agent", userAgent);
        }
        if (headers != null) {
            for (Map.Entry<String, Object> h : headers.entrySet()) {
                if (h.getValue() instanceof String) {
                    con.setRequestProperty(h.getKey(), (String) h.getValue());
                } else {
                    throw new IllegalArgumentException("invalid header of type " + h.getValue().getClass() + " name=" + h.getKey());
                }
            }
        }

        URL redirectTo = null;

        int httpCode = -1;
        String responseMessage = null;
        String contentType = null;
        String location = null;
        // Resource download
        try {
            try (InputStream res = con.getInputStream();) {
                if (httpCon != null) {
                    // HttpURLConnection cannot manage redirects from http to https well, so we do.
                    httpCode = httpCon.getResponseCode();
                    contentType = httpCon.getContentType();
                    responseMessage = httpCon.getResponseMessage();
                    location = httpCon.getHeaderField("Location");
                    if (instance_follow_redirects && httpCode >= 300 && httpCode < 400 && location != null && !location.isEmpty()) {
                        redirectTo = new URL(url, location);
                    }
                    String errorHeader = httpCon.getHeaderField("x-mn-error");
                    if (errorHeader != null && errorHeader.equals("notfound")) {
                        httpCode = HttpURLConnection.HTTP_NOT_FOUND;
                        throw new FileNotFoundException("404 Not Found (x-mn-error=" + errorHeader + ")");
                    }
                }
                if (redirectTo == null) {
                    long length = copyStreams(res, out);
                    if (httpCon != null) {
                        return new ResourceInfos(
                                url,
                                length,
                                contentType,
                                httpCode,
                                responseMessage,
                                location
                        );
                    } else {
                        return new ResourceInfos(
                                url,
                                length,
                                contentType,
                                httpCode,
                                responseMessage,
                                location
                        );
                    }
                }
            } catch (IOException ex) {
                if (retry_on_ssl_error_and_custom_verifier && disable_https_validation) {
                    Map<String, Object> newOptions = new HashMap<>(options);
                    newOptions.put("disable_https_validation", false);
                    newOptions.put("retry_on_ssl_error_and_custom_verifier", false);
                    return downloadFromUrl(url, out, newOptions);
                } else {
                    throw ex;
                }
            }
        } catch (IOException | RuntimeException ex) {
            Exception finalError = ex;
            String error = null;
            if (httpCon != null) {
                httpCode = httpCon.getResponseCode();
                responseMessage = httpCon.getResponseMessage();
                String errorHeader = httpCon.getHeaderField("x-mn-error");
                if (errorHeader != null && errorHeader.equals("notfound")) {
                    httpCode = HttpURLConnection.HTTP_NOT_FOUND;
                    finalError = new FileNotFoundException("404 Not Found (x-mn-error=" + errorHeader + ")");
                }
                try {
                    error = streamToString(httpCon.getErrorStream());
                } catch (IOException ex2) {
                    //peccato
                }
            }

            if (return_errors) {
                return new ResourceInfos(url, -1, null, httpCode, responseMessage, null);
            }

            if (error != null) {
                if (httpCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    FileNotFoundException ff = new FileNotFoundException(error);
                    ff.initCause(finalError);
                    throw ff;
                } else {
                    throw new IOException(error, finalError);
                }
            } else if (!(finalError instanceof IOException)) {
                // examples of BUGS in URL/URLConnection IllegalArgumentException | java.lang.StringIndexOutOfBoundsException
                throw new IOException(ex);
            } else {
                throw ex;
            }
        } finally {
            if (httpCon != null) {
                httpCon.disconnect();
            }
        }

        if (redirectTo == null) {
            throw new IllegalStateException();
        }
        return downloadFromUrl(redirectTo, out, options);
    }

    public static long uploadToUrl(URL url, InputStream in, OutputStream out, Map<String, Object> options) throws IOException {

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        if (options == null) {
            options = Collections.emptyMap();
        }
        int read_timeout = 0;
        if (options.containsKey("read_timeout")) {
            read_timeout = Integer.parseInt(options.get("read_timeout") + "");
        }
        if (read_timeout <= 0) {
            read_timeout = 60000;
        }
        int connect_timeout = 0;
        if (options.containsKey("connect_timeout")) {
            connect_timeout = Integer.parseInt(options.get("connect_timeout") + "");
        }
        if (connect_timeout <= 0) {
            connect_timeout = 60000;
        }

        boolean disable_https_validation = false;
        boolean verify_ssl_hostnames = false;
        if (options.containsKey("disable_https_validation")) {
            disable_https_validation = Boolean.parseBoolean(options.get("disable_https_validation") + "");
        }
        if (options.containsKey("verify_ssl_hostnames")) {
            verify_ssl_hostnames = Boolean.parseBoolean(options.get("verify_ssl_hostnames") + "");
        }
        if (disable_https_validation) {
            HttpTestUtils.tweakConnection(con, disable_https_validation, verify_ssl_hostnames);
        }
        con.setReadTimeout(read_timeout);
        con.setConnectTimeout(connect_timeout);

        con.setDoInput(true);
        con.setDoOutput(true);
        String method = "POST";
        if (options.containsKey("method")) {
            method = (String) options.get("method");
        }
        con.setRequestMethod(method.toUpperCase());

        if (options.containsKey("content_type")) {
            String contentType = (String) options.get("content_type");
            con.setRequestProperty("Content-Type", contentType);
        }

        if (options.containsKey("content_length")) {
            Number contentLength = (Number) options.get("content_length");
            if (contentLength != null) {
                con.setRequestProperty("Content-Length", contentLength.longValue() + "");
            }
        }

        Map<String, Object> headers = null;
        if (options.containsKey("headers")) {
            headers = (Map<String, Object>) options.get("headers");
        }
        if (headers != null) {
            for (Map.Entry<String, Object> h : headers.entrySet()) {
                if (h.getValue() instanceof String) {
                    con.setRequestProperty(h.getKey(), (String) h.getValue());
                } else {
                    throw new IllegalArgumentException("invalid header of type " + h.getValue().getClass() + " name=" + h.getKey());
                }
            }
        }
        if (in != null) {
            try (OutputStream postBody = con.getOutputStream()) {
                copyStreams(in, postBody);
            }
        }

        try {
            try (InputStream res = con.getInputStream();) {
                return copyStreams(res, out);
            }
        } catch (IOException ex) {
            String error = null;
            try {
                error = streamToString(con.getErrorStream());
            } catch (IOException ex2) {
                //peccato
            }
            if (error != null) {
                throw new IOException(error, ex);
            } else {
                throw ex;
            }
        }

    }

    private static String streamToString(InputStream in) throws IOException {
        try (BufferedInputStream ii = new BufferedInputStream(in);
                Reader r = new InputStreamReader(ii, StandardCharsets.UTF_8)) {
            StringWriter writer = new StringWriter();
            int c = r.read();
            while (c != -1) {
                writer.write((char) c);
                c = r.read();
            }
            return writer.toString();
        }
    }

    /**
     * To accept all SSL certificates
     */
    private static class MyTrustManager implements X509TrustManager {

        MyTrustManager() { // constructor
            // create/load keystore
        }

        @Override
        public void checkClientTrusted(X509Certificate chain[], String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate chain[], String authType) throws CertificateException {
            // special handling such as poping dialog boxes
            System.out.println("[SSL CLIENT] checkServerTrusted "+Arrays.toString(chain));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] res = new X509Certificate[0];
            return res;
        }
    }

    /**
     * Set connection parameters to avoid HTTPs certificate validity checking.
     * From 18.10 it can even manage read timeout and connection timeout when fulfilled.
     *
     * @param con
     */
    public static void tweakConnection(URLConnection con) {
        tweakConnection(con, true, false);
    }

    public static void tweakConnection(URLConnection con, boolean disablehttps, boolean verifysslhostames) {
        if (disablehttps && con instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) con;
            if (!verifysslhostames) {
                https.setHostnameVerifier(hostname_verifier);
            }
            https.setSSLSocketFactory(socket_factory);
        }

        if (con.getReadTimeout() <= 0) {
            con.setReadTimeout(60 * 1000); // 1 min
        }
        if (con.getConnectTimeout() <= 0) {
            con.setConnectTimeout(60 * 1000); // 1 min
        }
    }

    public static void setBasicCredentials(String httpProxyUsername, String httpProxyPassword, URLConnection con) {

        if (httpProxyUsername != null && httpProxyUsername.length() > 0) {
            String pHeader = "Authorization";
            String sstr = httpProxyUsername + ":" + httpProxyPassword;

            String secret = "Basic " + Base64.encodeBase64String(sstr.getBytes(StandardCharsets.UTF_8));
            con.addRequestProperty(pHeader, secret);
        }
    }

    public static void overideJvmWideHttpsVerifier() {
        HttpsURLConnection.setDefaultHostnameVerifier(hostname_verifier);
        HttpsURLConnection.setDefaultSSLSocketFactory(socket_factory);
    }    

}
