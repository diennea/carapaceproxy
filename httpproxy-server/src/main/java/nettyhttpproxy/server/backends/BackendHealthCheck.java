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
package nettyhttpproxy.server.backends;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import nettyhttpproxy.utils.IOUtils;

/**
 *
 * @author francesco.caliumi
 */
public class BackendHealthCheck {
    
    public final static int RESULT_SUCCESS = 1;
    public final static int RESULT_FAILURE_CONNECTION = 2;
    public final static int RESULT_FAILURE_STATUS = 3;
    
    private final long startTs;
    private final long endTs;
    private final int result;
    private final String resultStr;

    private BackendHealthCheck(long startTs, long endTs, int result, String resultStr) {
        this.startTs = startTs;
        this.endTs = endTs;
        this.result = result;
        this.resultStr = resultStr;
    }

    @Override
    public String toString() {
        return "BackendHealtCheck{" + "startTs=" + startTs + ", endTs=" + endTs + ", responseTime=" + getResponseTime() + ", result=" + result + ", resultStr=" + resultStr + '}';
    }

    public long getStartTs() {
        return startTs;
    }

    public long getEndTs() {
        return endTs;
    }
    
    public long getResponseTime() {
        return endTs - startTs;
    }

    public int getResult() {
        return result;
    }

    public String getResultStr() {
        return resultStr;
    }
    
    public boolean isOk() {
        return result == RESULT_SUCCESS;
    }
    
    public static BackendHealthCheck check(String host, int port, String path, int timeoutMillis) {
       
        if (path == null || path.isEmpty()) {
            long now = System.currentTimeMillis();
            return new BackendHealthCheck(now, now, RESULT_SUCCESS, "MOCK OK");
        
        } else {
            long startts = System.currentTimeMillis();
            URL url;
            HttpURLConnection httpConn = null;
            try {
                url = new URL("http", host, port, path);
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
                
                try (InputStream is = httpConn.getInputStream();) {
                    int httpCode = httpConn.getResponseCode();
                    String httpBody = IOUtils.toString(is, StandardCharsets.UTF_8);
                    if (httpCode >= 200 && httpCode <= 299) {
                        return new BackendHealthCheck(startts, System.currentTimeMillis(), RESULT_SUCCESS, httpBody);
                    } else {
                        return new BackendHealthCheck(
                            startts, System.currentTimeMillis(), RESULT_FAILURE_STATUS, 
                                "HttpCode="+httpCode+", HttpMessage="+httpConn.getResponseMessage()+", HttpBody="+httpBody);
                    }
                }

            } catch (MalformedURLException ex) {
                throw new RuntimeException(ex);
                
            } catch (IOException | RuntimeException ex) {
                int httpCode = 0;
                String httpMsg = null;
                String httpErrorBody = null;
                
                if (httpConn != null) {
                    try {
                        httpCode = httpConn.getResponseCode();
                        httpMsg = httpConn.getResponseMessage();
                    } catch (IOException ex2) {
                        // Ignore
                    }
                    
                    try {
                        httpErrorBody = IOUtils.toString(httpConn.getErrorStream(), StandardCharsets.UTF_8);
                    } catch (IOException ex2) {
                        // Ignore
                    }
                }
                
                if (httpCode > 0) {
                    return new BackendHealthCheck(
                        startts, System.currentTimeMillis(), RESULT_FAILURE_STATUS,
                            "HttpCode="+httpCode+", HttpMsg="+httpMsg+", httpErrorBody="+httpErrorBody);
                } else {
                    return new BackendHealthCheck(
                        startts, System.currentTimeMillis(), RESULT_FAILURE_CONNECTION, 
                            ex.getMessage());
                }
            }
        }
    }
}
