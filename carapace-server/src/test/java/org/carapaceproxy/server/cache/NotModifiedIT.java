package org.carapaceproxy.server.cache;

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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class NotModifiedIT {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    @TempDir
    public File tmpDir;

    @Test
    void serveFromCacheAnswer304() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withHeader("Last-Modified", HttpUtils.formatDateHeader(new java.util.Date(System.currentTimeMillis() - 60000)))
                        .withBody("it <b>works</b> !!")
                ));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), true, false);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String s = resp.toString();
                System.out.println("s:" + s);
                assertThat(s).contains("it <b>works</b> !!");
                resp.getHeaderLines().forEach(h ->
                    System.out.println("HEADER LINE :" + h));
                assertThat(resp.getHeaderLines()).noneMatch(h -> h.contains("X-Cached"));
            }

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                {
                    RawHttpClient.HttpResponse resp = client.executeRequest("GET /index.html HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "If-Modified-Since: " + HttpUtils.formatDateHeader(new java.util.Date(System.currentTimeMillis())) + "\r\n"
                            + "\r\n");
                    assertThat(resp.getStatusLine().trim()).isEqualTo("HTTP/1.1 304 Not Modified");
                    resp.getHeaderLines().forEach(h ->
                        System.out.println("HEADER LINE :" + h));
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("X-Cached"));
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("expires"));
                    assertThat(resp.getHeaderLines()).anyMatch(h -> h.contains("last-modified"));
                    assertThat(resp.getBodyString()).isEmpty();
                }
            }

            assertThat(server.getCache().getCacheSize()).isOne();
            assertThat(server.getCache().getStats().getHits()).isOne();
            assertThat(server.getCache().getStats().getMisses()).isOne();
        }
    }


}
