package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.Options.DYNAMIC_PORT;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_COUNT;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_IDLE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_INTERVAL;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_MAX_KEEP_ALIVE_REQUESTS;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SO_BACKLOG;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.util.Set;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

public class Http2HeadersTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void test() throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!"))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(new NetworkListenerConfiguration(
                    "localhost",
                    DYNAMIC_PORT,
                    false,
                    null,
                    null,
                    DEFAULT_SSL_PROTOCOLS,
                    DEFAULT_SO_BACKLOG,
                    DEFAULT_KEEP_ALIVE,
                    DEFAULT_KEEP_ALIVE_IDLE,
                    DEFAULT_KEEP_ALIVE_INTERVAL,
                    DEFAULT_KEEP_ALIVE_COUNT,
                    DEFAULT_MAX_KEEP_ALIVE_REQUESTS,
                    DEFAULT_FORWARDED_STRATEGY,
                    Set.of(),
                    Set.of(HttpProtocol.H2C.name(), HttpProtocol.HTTP11.name())
            ));

            server.start();
            final var port = server.getLocalPort();
            final HttpClientResponse response = HttpClient.create()
                    .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
                    .headers(headers -> headers
                            .add(HttpHeaderNames.CONNECTION, "keep-alive")
                            .add(HttpHeaderNames.CONNECTION, "upgrade")
                            .add(HttpHeaderNames.UPGRADE, "HTTP/2.0")
                    )
                    .get()
                    .uri("http://localhost:" + port + "/index.html")
                    .response()
                    .block();
            assertThat(response, is(notNullValue()));
            assertThat(response.status(), is(HttpResponseStatus.OK));
            assertThat(response.version(), is(HttpVersion.HTTP_1_1));
        }
    }
}
