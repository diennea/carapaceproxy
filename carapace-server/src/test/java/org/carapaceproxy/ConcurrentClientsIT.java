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

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

/**
 *
 * @author enrico.olivelli
 */
public class ConcurrentClientsIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @ParameterizedTest(name = "concurrent={0}")
    @ValueSource(booleans = {true, false})
    void clients(final boolean concurrent) throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        final TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir)) {
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
