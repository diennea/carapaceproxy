package nettyhttpproxy;

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
import nettyhttpproxy.server.HttpProxyServer;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.server.config.NetworkListenerConfiguration;
import org.apache.commons.io.IOUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ConcurrentClientsTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("it <b>works</b> !!")));

        int port = 1234;
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        EndpointKey key = new EndpointKey("localhost", wireMockRule.port(), false);

        int size = 100;
        int concurrentClients = 4;
        ConnectionsManagerStats stats;
        try (HttpProxyServer server = new HttpProxyServer("localhost", port, mapper);) {
            server.start();

            ExecutorService threadPool = Executors.newFixedThreadPool(concurrentClients);

            CountDownLatch count = new CountDownLatch(size);
            for (int i = 0; i < size; i++) {
                threadPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8");
//                            System.out.println("s:" + s);
                            assertEquals("it <b>works</b> !!", s);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            fail();
                        }
                        count.countDown();
                    }
                });
            }
            threadPool.shutdown();
            assertTrue(threadPool.awaitTermination(1, TimeUnit.MINUTES));
            assertTrue(count.await(1, TimeUnit.MINUTES));

            stats = server.getConnectionsManager().getStats();
            assertNotNull(stats.getEndpoints().get(key));
            TestUtils.waitForCondition(() -> {
                stats.getEndpoints().values().forEach((EndpointStats st) -> {
                    System.out.println("st2:" + st);
                });
                EndpointStats epstats = stats.getEndpointStats(key);
                return epstats.getTotalConnections().intValue() >= concurrentClients
                    && epstats.getActiveConnections().intValue() == 0
                    && epstats.getOpenConnections().intValue() >= concurrentClients
                    && epstats.getTotalRequests().intValue() == size;
            }, 100);
        }

        TestUtils.waitForCondition(() -> {
            EndpointStats epstats = stats.getEndpointStats(key);
            return epstats.getTotalConnections().intValue() >= concurrentClients
                && epstats.getActiveConnections().intValue() == 0
                && epstats.getOpenConnections().intValue() == 0
                && epstats.getTotalRequests().intValue() == size;
        }, 100);

        TestUtils.waitForCondition(TestUtils.ALL_CONNECTIONS_CLOSED(stats), 100);

    }

}
