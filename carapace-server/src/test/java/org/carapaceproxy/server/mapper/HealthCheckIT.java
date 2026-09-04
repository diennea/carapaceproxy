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
package org.carapaceproxy.server.mapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.util.Map;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.backends.BackendHealthCheck;
import org.carapaceproxy.server.backends.BackendHealthCheck.Result;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 *
 * @author francesco.caliumi
 */
public class HealthCheckIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @Test
    void test() throws Exception {
        final BackendConfiguration b1conf = new BackendConfiguration("myid", "localhost", wireMockRule.getPort(), "/status.html", -1);
        final EndpointMapper mapper = new TestEndpointMapper(b1conf, false);
        final RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
        final BackendHealthManager hman = new BackendHealthManager(conf, mapper);
        {
            // Backend returns 200 OK, making it available.
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("Ok..."))
            );

            final long startTs = System.currentTimeMillis();
            hman.run();
            final long endTs = System.currentTimeMillis();

            final Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status).hasSize(1);

            assertStaticBackendConfig(mapper, b1conf.id());

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.COLD);
            assertThat(_status.getUnreachableSince()).isZero();
            assertThat(_status.getLastUnreachable()).isLessThan(_status.getLastReachable());
            assertThat(_status.getLastReachable())
                    .isBetween(startTs, endTs);

            assertLastProbe(_status, Result.SUCCESS, "200 OK", "Ok...", startTs, endTs);
        }
        {
            // Backend returns 500, marking it unavailable.
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("ERROR"))
            );

            final long startTs = System.currentTimeMillis();
            hman.run();
            final long endTs = System.currentTimeMillis();

            final Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status).hasSize(1);

            assertStaticBackendConfig(mapper, b1conf.id());

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.DOWN);
            assertThat(_status.getLastReachable())
                    .isLessThanOrEqualTo(startTs);
            assertThat(_status.getUnreachableSince())
                    .isBetween(startTs, endTs);
            assertThat(_status.getLastUnreachable()).isEqualTo(_status.getUnreachableSince());

            assertLastProbe(_status, Result.FAILURE_STATUS, "500 Server Error", "ERROR", startTs, endTs);
        }
        {
            // Backend remains in error, keeping it unreachable.
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("ERROR"))
            );

            final long startTs = System.currentTimeMillis();
            hman.run();
            final long endTs = System.currentTimeMillis();

            final Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status).hasSize(1);

            assertStaticBackendConfig(mapper, b1conf.id());

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.DOWN);
            assertThat(_status.getLastReachable())
                    .isLessThanOrEqualTo(startTs);
            assertThat(_status.getUnreachableSince())
                    .isLessThanOrEqualTo(startTs);
            assertThat(_status.getLastUnreachable())
                    .isBetween(startTs, endTs);

            assertLastProbe(_status, Result.FAILURE_STATUS, "500 Server Error", "ERROR", startTs, endTs);
        }
        {
            // Backend recovers and returns 201, marking it available again.
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withBody("Ok..."))
            );

            final long startTs = System.currentTimeMillis();
            hman.run();
            final long endTs = System.currentTimeMillis();

            final Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status).hasSize(1);

            assertStaticBackendConfig(mapper, b1conf.id());

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.COLD);
            assertThat(_status.getUnreachableSince()).isZero();
            assertThat(_status.getLastUnreachable())
                    .isLessThanOrEqualTo(startTs);
            assertThat(_status.getLastReachable())
                    .isBetween(startTs, endTs);

            assertLastProbe(_status, Result.SUCCESS, "201 Created", "Ok...", startTs, endTs);
        }
    }

    private void assertStaticBackendConfig(final EndpointMapper mapper, final String backendId) {
        final BackendConfiguration bconf = mapper.getBackends().get(backendId);
        assertThat(bconf.id()).isEqualTo("myid");
        assertThat(bconf.host()).isEqualTo("localhost");
        assertThat(bconf.port()).isEqualTo(wireMockRule.getPort());
        assertThat(bconf.probePath()).isEqualTo("/status.html");
    }

    /** Every probe in this test hits /status.html; only the outcome and the response differ. */
    private static void assertLastProbe(final BackendHealthStatus status, final Result result,
                                        final String httpResponse, final String httpBody,
                                        final long startTs, final long endTs) {
        final BackendHealthCheck lastProbe = status.getLastProbe();
        assertThat(lastProbe)
                .extracting(BackendHealthCheck::path, BackendHealthCheck::result,
                        BackendHealthCheck::httpResponse, BackendHealthCheck::httpBody)
                .containsExactly("/status.html", result, httpResponse, httpBody);
        assertThat(lastProbe.endTs()).isBetween(startTs, endTs);
    }

}
