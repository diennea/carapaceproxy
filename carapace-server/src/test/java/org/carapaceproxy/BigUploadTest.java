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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

/**
 * Tests the proxy's behavior with large uploads and backend failures.
 *
 * @author enrico.olivelli
 */
public class BigUploadTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

    private HttpProxyServer server;

    @Before
    public void setUp() throws Exception {
        final TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder());
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void testProxyReturns503OnBackendConnectionDrop() {
        wireMockRule.stubFor(post(urlEqualTo("/index.html"))
                // backend server abruptly closes the connection while the client is sending a large upload
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER).withFixedDelay(2000)));
        // Create a large, streaming request body (20MB).
        final byte[] chunk = new byte[1024];
        final Flux<ByteBuf> requestBody = Flux.range(0, 20_000).map(i -> Unpooled.wrappedBuffer(chunk));
        final HttpClient client = HttpClient.create();
        final Mono<Tuple2<HttpClientResponse, String>> result = client
                .post()
                .uri("http://localhost:" + server.getLocalPort() + "/index.html")
                .send(requestBody)
                .responseSingle((response, bodyMono) -> Mono.zip(Mono.just(response), bodyMono.asString()));
        StepVerifier.create(result)
                .assertNext(tuple -> {
                    final HttpClientResponse response = tuple.getT1();
                    final String body = tuple.getT2();
                    assertEquals(503, response.status().code());
                    assertTrue(body.contains("Service Unavailable"));
                })
                .verifyComplete();
    }
}
