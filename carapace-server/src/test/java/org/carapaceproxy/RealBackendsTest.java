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
package org.carapaceproxy;

import static org.junit.Assert.assertFalse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author nicolo.boschi
 */
public class RealBackendsTest {

    private static RawHttpClient.HttpResponse doGet(RawHttpClient client, String host, String uri) throws IOException {
        RawHttpClient.HttpResponse response =
                client.executeRequest("GET " + uri + " HTTP/1.1"
                        + "\r\nHost: " + host
                        + "\r\nAccept-Encoding: gzip, deflate, br"
                        + "\r\nCache-Control: no-cache"
                        + "\r\nPragma: no-cache"
                        + "\r\nConnection: keep-alive"
                        + "\r\n\r\n"
                );
        return response;

    }

    private static RawHttpClient.HttpResponse doPost(RawHttpClient client, String host, String auth, String uri, String body) throws IOException {
        RawHttpClient.HttpResponse response =
                client.executeRequest("POST " + uri + " HTTP/1.1"
                        + "\r\nHost: " + host
                        + "\r\nAuthorization: Bearer " + auth
                        + "\r\nAccept: application/json"
                        + "\r\nAccept-Encoding: gzip, deflate, br"
                        + "\r\nCache-Control: no-cache"
                        + "\r\nConnection: keep-alive"
                        + "\r\nContent-Type: application/json"
                        + "\r\nContent-Length: " + body.length()
                        + "\r\n\r\n"
                        + body
                );
        return response;

    }

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    @Ignore
    public void testRequestsRealBackend() throws Exception {
        String host = "";
        int threads = 15;
        final int requestsPerClient = 1000;
        TestEndpointMapper mapper = new TestEndpointMapper(host, 8443);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future> futures = new ArrayList<>();
        AtomicInteger countOk = new AtomicInteger();
        AtomicInteger countError = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        final String carapaceHost = "localhost";
        int port = 443;
        boolean isLocal = carapaceHost.equals("localhost");

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            RuntimeServerConfiguration config = new RuntimeServerConfiguration();
            config.setMaxConnectionsPerEndpoint(1);
            server.getProxyRequestsManager().reloadConfiguration(config, mapper.getBackends().values());
            server.start();
            if (isLocal) {
                port = server.getLocalPort();
            }
            final int carapaceport = port;

            try {

                for (int i = 0; i < threads; i++) {
                    futures.add(ex.submit(() -> {

                        try {
                            try (RawHttpClient client = new RawHttpClient(carapaceHost, carapaceport, !isLocal)) {
                                client.getSocket().setKeepAlive(true);
                                client.getSocket().setSoTimeout(1000 * 30);
                                for (int rq = 0; rq < requestsPerClient; rq++) {
                                    if (stop.get()) {
                                        throw new RuntimeException("stopped");
                                    }
                                    {
                                        RawHttpClient.HttpResponse response = doGet(client, carapaceHost, "changeit");
                                        String res = response.getBodyString();
                                        if (response.getStatusLine().contains("200")) {
                                            System.out.println("Thread " + Thread.currentThread().getName() + " DONE #" + rq);
                                            countOk.incrementAndGet();
                                        } else {
                                            System.out.println("bad response:" + res + "\n" + response);
                                            countError.incrementAndGet();
                                            stop.set(true);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            countError.incrementAndGet();
                            System.out.println("Bad err!!" + e);
                            throw new RuntimeException(e);
                        }
                    }));

                }

            } finally {
                for (Future future : futures) {
                    try {
                        future.get();
                    } catch (Throwable e) {
                        System.out.println("ERR" + e);
                        System.out.println(e);
                    }
                }
                System.out.println("WAIT FOR TERMINATION");
                ex.shutdown();
                ex.awaitTermination(1, TimeUnit.MINUTES);
            }
        }
        System.out.println("RESULTS: OK " + countOk.get() + ", ERR " + countError.get());
        assertFalse(countError.get() > 0);
    }

}
