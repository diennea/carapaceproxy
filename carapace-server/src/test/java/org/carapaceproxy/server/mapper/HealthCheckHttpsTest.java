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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.File;
import java.util.Map;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.backends.BackendHealthCheck;
import org.carapaceproxy.server.backends.BackendHealthManager;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * HTTPS health-check tests:
 *  - Success with probescheme=https and private CA configured.
 *  - Failure with probescheme=https and no CA against a private-CA endpoint.
 */
public class HealthCheckHttpsTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void httpsProbe_withPrivateCA_succeeds() throws Exception {
        final String backendKeystoreAbsolutePath = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        final WireMockConfiguration wm = options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(backendKeystoreAbsolutePath)
                .keystoreType("PKCS12")
                .keystorePassword("changeit")
                .keyManagerPassword("changeit");

        final WireMockRule httpsBackend = new WireMockRule(wm);
        httpsBackend.start();
        try {
            final String path = "/health";
            httpsBackend.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("OK")));

            final int port = httpsBackend.httpsPort();
            final String caFileNameOnly = new File(backendKeystoreAbsolutePath).getName();

            final BackendConfiguration bconf = new BackendConfiguration(
                    "backend-https-ok",
                    new org.carapaceproxy.core.EndpointKey("localhost", port),
                    path,
                    -1,
                    true,
                    caFileNameOnly,
                    "changeit",
                    "https"
            );

            final EndpointMapper mapper = new TestEndpointMapper(bconf, false);

            final RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
            final BackendHealthManager hman = new BackendHealthManager(conf, mapper, tmpDir.getRoot());

            hman.run();

            final Map<EndpointKey, BackendHealthStatus> snapshot = hman.getBackendsSnapshot();
            final BackendHealthStatus status = snapshot.get(bconf.hostPort());
            assertThat(status, is(not(nullValue())));

            final BackendHealthCheck lastProbe = status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            assertThat("HTTPS health check with CA must succeed", lastProbe.ok(), is(true));
            assertThat(lastProbe.httpResponse(), is("200 OK"));
        } finally {
            httpsBackend.stop();
        }
    }

    @Test
    public void httpsProbe_withoutCA_againstPrivateCert_fails() throws Exception {
        final String backendKeystoreAbsolutePath = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        final WireMockConfiguration wm = options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(backendKeystoreAbsolutePath)
                .keystoreType("PKCS12")
                .keystorePassword("changeit")
                .keyManagerPassword("changeit");

        final WireMockRule httpsBackend = new WireMockRule(wm);
        httpsBackend.start();
        try {
            final String path = "/health";
            httpsBackend.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("OK")));

            final int port = httpsBackend.httpsPort();

            final BackendConfiguration bconf = new BackendConfiguration(
                    "backend-https-fail",
                    new org.carapaceproxy.core.EndpointKey("localhost", port),
                    path,
                    -1,
                    true,
                    null,      // no CA configured
                    null,
                    "https"
            );

            final EndpointMapper mapper = new TestEndpointMapper(bconf, false);

            final RuntimeServerConfiguration conf = new RuntimeServerConfiguration();
            final BackendHealthManager hman = new BackendHealthManager(conf, mapper, tmpDir.getRoot());

            hman.run();

            final Map<EndpointKey, BackendHealthStatus> snapshot = hman.getBackendsSnapshot();
            final BackendHealthStatus status = snapshot.get(bconf.hostPort());
            assertThat(status, is(not(nullValue())));

            final BackendHealthCheck lastProbe = status.getLastProbe();
            assertThat(lastProbe, is(not(nullValue())));
            // With private CA and no truststore configured, probe should fail at connection/trust layer
            assertThat("HTTPS health check without CA must fail", lastProbe.ok(), is(false));
        } finally {
            httpsBackend.stop();
        }
    }
}
