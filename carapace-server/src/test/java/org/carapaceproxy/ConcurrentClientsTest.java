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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.util.function.Function;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

/**
 *
 * @author enrico.olivelli
 */
@RunWith(JUnitParamsRunner.class)
public class ConcurrentClientsTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    @Parameters({"true", "false"})
    public void testClients(final boolean concurrent) throws ConfigurationNotValidException, InterruptedException, IOException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        final TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            final int numRequests = 200;
            final int concurrency = concurrent ? 4 : 1;
            final ConnectionProvider provider = ConnectionProvider
                    .builder("test-pool")
                    .maxConnections(concurrency)
                    .pendingAcquireMaxCount(-1 /* no upper limit in the queue of registered requests for acquire */)
                    .build();
            final HttpClient client = HttpClient.create(provider);
            final Function<Integer, Mono<String>> executeRequest = i -> client
                    .get()
                    .uri("http://localhost:" + server.getLocalPort() + "/index.html")
                    .responseContent()
                    .aggregate()
                    .asString()
                    .onErrorMap(ex -> new RuntimeException("Request " + i + " failed", ex));
            final Flux<String> results = Flux.range(0, numRequests).flatMap(executeRequest, concurrency);
            StepVerifier.create(results)
                    .expectNextCount(numRequests)
                    .verifyComplete();
        }
    }
}
