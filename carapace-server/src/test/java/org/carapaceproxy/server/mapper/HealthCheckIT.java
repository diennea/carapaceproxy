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
            assertThat(_status).isNotNull();
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.COLD);
            assertThat(_status.getUnreachableSince()).isZero();
            assertThat(_status.getLastUnreachable()).isLessThan(_status.getLastReachable());
            assertThat(_status.getLastReachable())
                    .isBetween(startTs, endTs);

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe).isNotNull();
            assertThat(lastProbe.path()).isEqualTo("/status.html");
            assertThat(lastProbe.endTs()).isGreaterThanOrEqualTo(startTs);
            assertThat(lastProbe.endTs()).isLessThanOrEqualTo(endTs);
            assertThat(lastProbe.ok()).isTrue();
            assertThat(lastProbe.httpResponse()).isEqualTo("200 OK");
            assertThat(lastProbe.httpBody()).isEqualTo("Ok...");
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
            assertThat(_status).isNotNull();
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.DOWN);
            assertThat(_status.getLastReachable())
                    .isLessThanOrEqualTo(startTs)
                    .isLessThanOrEqualTo(endTs);
            assertThat(_status.getUnreachableSince())
                    .isBetween(startTs, endTs);
            assertThat(_status.getLastUnreachable()).isEqualTo(_status.getUnreachableSince());

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe).isNotNull();
            assertThat(lastProbe.path()).isEqualTo("/status.html");
            assertThat(lastProbe.endTs()).isGreaterThanOrEqualTo(startTs);
            assertThat(lastProbe.endTs()).isLessThanOrEqualTo(endTs);
            assertThat(lastProbe.ok()).isFalse();
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse()).isEqualTo("500 Server Error");
            assertThat(lastProbe.httpBody()).isEqualTo("ERROR");
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
            assertThat(_status).isNotNull();
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.DOWN);
            assertThat(_status.getLastReachable())
                    .isLessThanOrEqualTo(startTs)
                    .isLessThanOrEqualTo(endTs);
            assertThat(_status.getUnreachableSince())
                    .isLessThanOrEqualTo(startTs)
                    .isLessThanOrEqualTo(endTs);
            assertThat(_status.getLastUnreachable())
                    .isBetween(startTs, endTs);

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe).isNotNull();
            assertThat(lastProbe.path()).isEqualTo("/status.html");
            assertThat(lastProbe.endTs()).isGreaterThanOrEqualTo(startTs);
            assertThat(lastProbe.endTs()).isLessThanOrEqualTo(endTs);
            assertThat(lastProbe.ok()).isFalse();
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse()).isEqualTo("500 Server Error");
            assertThat(lastProbe.httpBody()).isEqualTo("ERROR");
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
            assertThat(_status).isNotNull();
            assertThat(_status.getHostPort()).isEqualTo(b1conf.hostPort());
            assertThat(_status.getStatus()).isEqualTo(BackendHealthStatus.Status.COLD);
            assertThat(_status.getUnreachableSince()).isZero();
            assertThat(_status.getLastUnreachable())
                    .isLessThanOrEqualTo(startTs)
                    .isLessThanOrEqualTo(endTs);
            assertThat(_status.getLastReachable())
                    .isBetween(startTs, endTs);

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe).isNotNull();
            assertThat(lastProbe.path()).isEqualTo("/status.html");
            assertThat(lastProbe.endTs()).isGreaterThanOrEqualTo(startTs);
            assertThat(lastProbe.endTs()).isLessThanOrEqualTo(endTs);
            assertThat(lastProbe.ok()).isTrue();
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse()).isEqualTo("201 Created");
            assertThat(lastProbe.httpBody()).isEqualTo("Ok...");
        }
    }

    private void assertStaticBackendConfig(final EndpointMapper mapper, final String backendId) {
        final BackendConfiguration bconf = mapper.getBackends().get(backendId);
        assertThat(bconf.id()).isEqualTo("myid");
        assertThat(bconf.host()).isEqualTo("localhost");
        assertThat(bconf.port()).isEqualTo(wireMockRule.getPort());
        assertThat(bconf.probePath()).isEqualTo("/status.html");
    }
}
