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
package org.carapaceproxy.backends;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ProxyRequestsManager;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class UnreachableBackendTest {

    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {true /*
             * useCache = true
             */}, {false /*
             * useCache = false
             */}
        });
    }

    @RegisterExtension
    public static WireMockExtension wireMockRule = WireMockExtension.newInstance().options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    File tmpDir;

    private boolean useCache = false;

    public void initUnreachableBackendTest(boolean useCache) {
        this.useCache = useCache;
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testWithUnreachableBackend(boolean useCache) throws Exception {

        initUnreachableBackendTest(useCache);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        int dummyport = wireMockRule.getPort();
        // wireMockRule.stop();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"))) {
            // server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        Service Unavailable\n"
                        + "    </body>\n"
                        + "</html>\n", resp.getBodyString());
            }
            assertFalse(server.getBackendHealthManager().isAvailable(key.getHostPort()));
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testEmptyResponse(boolean useCache) throws Exception {

        initUnreachableBackendTest(useCache);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        int dummyport = wireMockRule.getPort();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = new HttpProxyServer(mapper, newFolder(tmpDir, "junit"));) {
            Properties properties = new Properties();
            // configure resets all listeners configurations
            server.configureAtBoot(new PropertiesConfigurationStore(properties));
            server.addListener(new NetworkListenerConfiguration("localhost", 0));
            server.setMapper(mapper);
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        Service Unavailable\n"
                        + "    </body>\n"
                        + "</html>\n", resp.getBodyString());
            }
            assertTrue(server.getBackendHealthManager().isAvailable(key.getHostPort())); // no troubles for new connections
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testConnectionResetByPeer(boolean useCache) throws Exception {

        initUnreachableBackendTest(useCache);

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        int dummyport = wireMockRule.getPort();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
            server.start();
            int port = server.getLocalPort();
            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        Service Unavailable\n"
                        + "    </body>\n"
                        + "</html>\n", resp.getBodyString());
            }
            assertTrue(server.getBackendHealthManager().isAvailable(key.getHostPort())); // no troubles for new connections
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    @MethodSource("data")
    @ParameterizedTest
    public void testNonHttpResponseThenClose(boolean useCache) throws Exception {
        initUnreachableBackendTest(useCache);
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        int dummyport = wireMockRule.getPort();
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", dummyport, useCache);
        EndpointKey key = new EndpointKey("localhost", dummyport);

        try (HttpProxyServer server = new HttpProxyServer(mapper, newFolder(tmpDir, "junit"));) {
            Properties properties = new Properties();
            server.configureAtBoot(new PropertiesConfigurationStore(properties));
            server.addListener(new NetworkListenerConfiguration("localhost", 0));
            server.setMapper(mapper);
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertEquals("HTTP/1.1 503 Service Unavailable\r\n", resp.getStatusLine());
                assertEquals("<html>\n"
                        + "    <body>\n"
                        + "        Service Unavailable\n"
                        + "    </body>\n"
                        + "</html>\n", resp.getBodyString());
            }
            assertTrue(server.getBackendHealthManager().isAvailable(key.getHostPort()));
            assertThat((int) ProxyRequestsManager.PENDING_REQUESTS_GAUGE.get(), is(0));
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
