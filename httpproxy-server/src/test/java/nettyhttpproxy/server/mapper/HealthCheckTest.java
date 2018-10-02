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
package nettyhttpproxy.server.mapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.HashMap;
import java.util.Map;
import nettyhttpproxy.EndpointMapper;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.server.backends.BackendHealthManager;
import nettyhttpproxy.server.backends.BackendHealthStatus;
import nettyhttpproxy.server.config.BackendConfiguration;
import nettyhttpproxy.utils.TestEndpointMapper;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
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
        backends.put(b1conf.getHostPort(), b1conf);

        EndpointMapper mapper = new TestEndpointMapper(null, 0, false, backends);
        RuntimeServerConfiguration conf = new RuntimeServerConfiguration();

        StatsLogger statsLogger = new NullStatsLogger();
        BackendHealthManager hman = new BackendHealthManager(conf, mapper, statsLogger);

        {
            stubFor(get(urlEqualTo("/status.html"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("Ok"))
            );

            long startTs = System.currentTimeMillis();
            hman.run();
            long endTs = System.currentTimeMillis();

            Map<String, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));
            assertThat(status.get(b1conf.getHostPort()), is(not(nullValue())));
            assertThat(status.get(b1conf.getHostPort()).getId(), is(b1conf.getHostPort()));
            BackendConfiguration bconf = mapper.getBackends().get(b1conf.getHostPort());
            assertThat(bconf.getId(), is("myid"));
            assertThat(bconf.getHost(), is("localhost"));
            assertThat(bconf.getPort(), is(wireMockRule.port()));
            assertThat(bconf.getProbePath(), is("/status.html"));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() >= startTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() <= endTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).isAvailable(), is(true));
            assertThat(status.get(b1conf.getHostPort()).isLastProbeSuccess(), is(true));
            assertThat(status.get(b1conf.getHostPort()).isReportedAsUnreachable(), is(false));
            assertThat(status.get(b1conf.getHostPort()).getReportedAsUnreachableTs(), is(0L));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeResult(), is("Ok"));
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

            Map<String, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));
            assertThat(status.get(b1conf.getHostPort()), is(not(nullValue())));
            assertThat(status.get(b1conf.getHostPort()).getId(), is(b1conf.getHostPort()));
            BackendConfiguration bconf = mapper.getBackends().get(b1conf.getHostPort());
            assertThat(bconf.getId(), is("myid"));
            assertThat(bconf.getHost(), is("localhost"));
            assertThat(bconf.getPort(), is(wireMockRule.port()));
            assertThat(bconf.getProbePath(), is("/status.html"));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() >= startTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() <= endTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).isAvailable(), is(false));
            assertThat(status.get(b1conf.getHostPort()).isLastProbeSuccess(), is(false));
            assertThat(status.get(b1conf.getHostPort()).isReportedAsUnreachable(), is(true));
            assertThat(status.get(b1conf.getHostPort()).getReportedAsUnreachableTs() >= startTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getReportedAsUnreachableTs() <= endTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeResult(), is("HttpCode=500, HttpMsg=Server Error, httpErrorBody=ERROR"));

            reportedAsUnreachableTs = status.get(b1conf.getHostPort()).getReportedAsUnreachableTs();
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

            Map<String, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));
            assertThat(status.get(b1conf.getHostPort()), is(not(nullValue())));
            assertThat(status.get(b1conf.getHostPort()).getId(), is(b1conf.getHostPort()));
            BackendConfiguration bconf = mapper.getBackends().get(b1conf.getHostPort());
            assertThat(bconf.getId(), is("myid"));
            assertThat(bconf.getHost(), is("localhost"));
            assertThat(bconf.getPort(), is(wireMockRule.port()));
            assertThat(bconf.getProbePath(), is("/status.html"));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() >= startTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() <= endTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).isAvailable(), is(false));
            assertThat(status.get(b1conf.getHostPort()).isLastProbeSuccess(), is(false));
            assertThat(status.get(b1conf.getHostPort()).isReportedAsUnreachable(), is(true));
            assertThat(status.get(b1conf.getHostPort()).getReportedAsUnreachableTs(), is(reportedAsUnreachableTs));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeResult(), is("HttpCode=500, HttpMsg=Server Error, httpErrorBody=ERROR"));
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

            Map<String, BackendHealthStatus> status = hman.getBackendsSnapshot();
            System.out.println("status=" + status);
            assertThat(status.size(), is(1));
            assertThat(status.get(b1conf.getHostPort()), is(not(nullValue())));
            assertThat(status.get(b1conf.getHostPort()).getId(), is(b1conf.getHostPort()));
            BackendConfiguration bconf = mapper.getBackends().get(b1conf.getHostPort());
            assertThat(bconf.getId(), is("myid"));
            assertThat(bconf.getHost(), is("localhost"));
            assertThat(bconf.getPort(), is(wireMockRule.port()));
            assertThat(bconf.getProbePath(), is("/status.html"));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() >= startTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeTs() <= endTs, is(true));
            assertThat(status.get(b1conf.getHostPort()).isAvailable(), is(true));
            assertThat(status.get(b1conf.getHostPort()).isLastProbeSuccess(), is(true));
            assertThat(status.get(b1conf.getHostPort()).isReportedAsUnreachable(), is(false));
            assertThat(status.get(b1conf.getHostPort()).getReportedAsUnreachableTs(), is(0L));
            assertThat(status.get(b1conf.getHostPort()).getLastProbeResult(), is("Ok..."));
        }

    }
}
