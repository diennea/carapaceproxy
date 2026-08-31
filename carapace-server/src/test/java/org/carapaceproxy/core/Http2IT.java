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
import static org.hamcrest.MatcherAssert.assertThat;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

@RunWith(JUnitParamsRunner.class)
public class Http2IT {

    public static final String RESPONSE = "it <b>works</b> !!";
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    public static Collection<Object[]> data() {
        return List.of(
                new Object[]{HttpProtocol.HTTP11, Set.of(HttpProtocol.HTTP11), false},
                new Object[]{HttpProtocol.H2C, Set.of(HttpProtocol.H2C), false},
                new Object[]{HttpProtocol.HTTP11, Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C), false},
                new Object[]{HttpProtocol.HTTP11, Set.of(HttpProtocol.HTTP11), true},
                new Object[]{HttpProtocol.H2C, Set.of(HttpProtocol.H2C), true},
                new Object[]{HttpProtocol.HTTP11, Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C), true}
        );
    }

    @Test
    @Parameters(method = "data")
    @TestCaseName("Client: {0}, Carapace conf: {1}, using cache: {2}")
    public void test(final HttpProtocol protocol, final Set<HttpProtocol> carapaceProtocols, final boolean withCache) throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf(RESPONSE.length()))
                        .withBody(RESPONSE))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port(), withCache, false);
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
                    carapaceProtocols,
                    new DefaultChannelGroup(new DefaultEventExecutor())));

            server.start();
            final var port = server.getLocalPort();
            assertThat(executeRequest(protocol, port), is(RESPONSE));
            if (withCache) {
                assertThat(executeRequest(protocol, port), is(RESPONSE));
            }
        }
    }

    private String executeRequest(final HttpProtocol protocol, final int port) {
        return HttpClient.create()
                .protocol(protocol)
                .get()
                .uri("http://localhost:" + port + "/index.html")
                .responseContent()
                .asString()
                .doOnNext(System.out::println)
                .blockFirst();
    }
}
