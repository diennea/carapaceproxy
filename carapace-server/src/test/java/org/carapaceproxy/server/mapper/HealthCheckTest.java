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

        Map<String, BackendConfiguration> backends = new HashMap<>();
        BackendConfiguration b1conf = new BackendConfiguration("myid", "localhost", wireMockRule.port(), "/status.html");
        backends.put(b1conf.hostPort().toString(), b1conf);

        EndpointMapper mapper = new TestEndpointMapper(null, 0, false, backends);
        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();

        BackendHealthManager hman = new BackendHealthManager(conf, mapper);

        {
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("Ok..."))
            );

            long startTs = System.currentTimeMillis();
            hman.run();
            long endTs = System.currentTimeMillis();

            Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));

            BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.isAvailable(), is(true));
            assertThat(_status.isReportedAsUnreachable(), is(false));
            assertThat(_status.getReportedAsUnreachableTs(), is(0L));

            BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe.getPath(), is("/status.html"));
            assertThat(lastProbe.getEndTs() >= startTs, is(true));
            assertThat(lastProbe.getEndTs() <= endTs, is(true));
            assertThat(lastProbe.isOk(), is(true));
            assertThat(lastProbe.getHttpResponse(), is("200 OK"));
            assertThat(lastProbe.getHttpBody(), is("Ok..."));
        }

        long reportedAsUnreachableTs;
        {
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("ERROR"))
            );

            long startTs = System.currentTimeMillis();
            hman.run();
            long endTs = System.currentTimeMillis();

            Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));

            BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.isAvailable(), is(false));
            assertThat(_status.isReportedAsUnreachable(), is(true));
            assertThat(_status.getReportedAsUnreachableTs() >= startTs, is(true));
            assertThat(_status.getReportedAsUnreachableTs() <= endTs, is(true));
            reportedAsUnreachableTs = _status.getReportedAsUnreachableTs();

            BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe.getPath(), is("/status.html"));
            assertThat(lastProbe.getEndTs() >= startTs, is(true));
            assertThat(lastProbe.getEndTs() <= endTs, is(true));
            assertThat(lastProbe.isOk(), is(false));
            System.out.println("HTTP MESSAGE: " + lastProbe.getHttpResponse());
            System.out.println("STATUS INFO: " + lastProbe.getHttpBody());
            assertThat(lastProbe.getHttpResponse(), is("500 Server Error"));
            assertThat(lastProbe.getHttpBody(), is("ERROR"));
        }

        {
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("ERROR"))
            );

            long startTs = System.currentTimeMillis();
            hman.run();
            long endTs = System.currentTimeMillis();

            Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));

            BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.isAvailable(), is(false));
            assertThat(_status.isReportedAsUnreachable(), is(true));
            assertThat(_status.getReportedAsUnreachableTs(), is(reportedAsUnreachableTs));

            BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe.getPath(), is("/status.html"));
            assertThat(lastProbe.getEndTs() >= startTs, is(true));
            assertThat(lastProbe.getEndTs() <= endTs, is(true));
            assertThat(lastProbe.isOk(), is(false));
            System.out.println("HTTP MESSAGE: " + lastProbe.getHttpResponse());
            System.out.println("STATUS INFO: " + lastProbe.getHttpBody());
            assertThat(lastProbe.getHttpResponse(), is("500 Server Error"));
            assertThat(lastProbe.getHttpBody(), is("ERROR"));
        }

        {
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withBody("Ok..."))
            );

            long startTs = System.currentTimeMillis();
            hman.run();
            long endTs = System.currentTimeMillis();

            Map<EndpointKey, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));

            BackendConfiguration bconf = mapper.getBackends().get(b1conf.hostPort().toString());
            assertThat(bconf.id(), is("myid"));
            assertThat(bconf.host(), is("localhost"));
            assertThat(bconf.port(), is(wireMockRule.port()));
            assertThat(bconf.probePath(), is("/status.html"));

            BackendHealthStatus _status = status.get(b1conf.hostPort());
            assertThat(_status, is(not(nullValue())));
            assertThat(_status.getHostPort(), is(b1conf.hostPort()));
            assertThat(_status.isAvailable(), is(true));
            assertThat(_status.isReportedAsUnreachable(), is(false));
            assertThat(_status.getReportedAsUnreachableTs(), is(0L));

            BackendHealthCheck lastProbe = _status.getLastProbe();
            assertThat(lastProbe.getPath(), is("/status.html"));
            assertThat(lastProbe.getEndTs() >= startTs, is(true));
            assertThat(lastProbe.getEndTs() <= endTs, is(true));
            assertThat(lastProbe.isOk(), is(true));
            System.out.println("HTTP MESSAGE: " + lastProbe.getHttpResponse());
            System.out.println("STATUS INFO: " + lastProbe.getHttpBody());
            assertThat(lastProbe.getHttpResponse(), is("201 Created"));
            assertThat(lastProbe.getHttpBody(), is("Ok..."));
        }

    }
}
