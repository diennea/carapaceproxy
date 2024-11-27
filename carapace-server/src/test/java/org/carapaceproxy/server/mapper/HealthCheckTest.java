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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.HashMap;
import java.util.Map;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.backends.BackendHealthCheck;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author francesco.caliumi
 */
public class HealthCheckTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws Exception {
        final Map<String, BackendConfiguration> backends = new HashMap<>();
        final BackendConfiguration b1conf = new BackendConfiguration("myid", "localhost", wireMockRule.port(), "/status.html");
        backends.put(b1conf.hostPort().toString(), b1conf);
        final EndpointMapper mapper = new TestEndpointMapper(null, 0, false, backends);
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
            assertThat(status.size(), is(1));

            final BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.getStatus() != BackendHealthStatus.Status.DOWN, is(true));
            assertThat(_status.getStatus() == BackendHealthStatus.Status.DOWN, is(false));
            assertThat(_status.getLastUnreachableTs(), is(0L));

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            assertThat(lastProbe.path(), is("/status.html"));
            assertThat(lastProbe.endTs() >= startTs, is(true));
            assertThat(lastProbe.endTs() <= endTs, is(true));
            assertThat(lastProbe.ok(), is(true));
            assertThat(lastProbe.httpResponse(), is("200 OK"));
            assertThat(lastProbe.httpBody(), is("Ok..."));
        }
        final long reportedAsUnreachableTs;
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
            assertThat(status.size(), is(1));

            final BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.getStatus() != BackendHealthStatus.Status.DOWN, is(false));
            assertThat(_status.getStatus() == BackendHealthStatus.Status.DOWN, is(true));
            assertThat(_status.getLastUnreachableTs() >= startTs, is(true));
            assertThat(_status.getLastUnreachableTs() <= endTs, is(true));
            reportedAsUnreachableTs = _status.getLastUnreachableTs();

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            assertThat(lastProbe.path(), is("/status.html"));
            assertThat(lastProbe.endTs() >= startTs, is(true));
            assertThat(lastProbe.endTs() <= endTs, is(true));
            assertThat(lastProbe.ok(), is(false));
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse(), is("500 Server Error"));
            assertThat(lastProbe.httpBody(), is("ERROR"));
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
            assertThat(status.size(), is(1));

            final BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.getStatus() != BackendHealthStatus.Status.DOWN, is(false));
            assertThat(_status.getStatus() == BackendHealthStatus.Status.DOWN, is(true));
            assertThat(_status.getLastUnreachableTs(), is(reportedAsUnreachableTs));

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            assertThat(lastProbe.path(), is("/status.html"));
            assertThat(lastProbe.endTs() >= startTs, is(true));
            assertThat(lastProbe.endTs() <= endTs, is(true));
            assertThat(lastProbe.ok(), is(false));
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse(), is("500 Server Error"));
            assertThat(lastProbe.httpBody(), is("ERROR"));
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
            assertThat(status.size(), is(1));

            final BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            final BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.getStatus() != BackendHealthStatus.Status.DOWN, is(true));
            assertThat(_status.getStatus() == BackendHealthStatus.Status.DOWN, is(false));
            assertThat(_status.getLastUnreachableTs(), is(0L));

            final BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            assertThat(lastProbe.path(), is("/status.html"));
            assertThat(lastProbe.endTs() >= startTs, is(true));
            assertThat(lastProbe.endTs() <= endTs, is(true));
            assertThat(lastProbe.ok(), is(true));
            System.out.println("HTTP MESSAGE: " + lastProbe.httpResponse());
            System.out.println("STATUS INFO: " + lastProbe.httpBody());
            assertThat(lastProbe.httpResponse(), is("201 Created"));
            assertThat(lastProbe.httpBody(), is("Ok..."));
        }
    }
}
