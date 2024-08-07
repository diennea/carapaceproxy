package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.Options.DYNAMIC_PORT;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.stream.Collectors.toUnmodifiableSet;
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
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

@RunWith(Parameterized.class)
public class Http2Test {

    public static final String RESPONSE = "it <b>works</b> !!";
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private final HttpProtocol protocol;
    private final Set<String> carapaceProtocols;
    private final boolean withCache;

    public Http2Test(final HttpProtocol protocol, final Set<HttpProtocol> carapaceProtocols, final boolean withCache) {
        this.protocol = protocol;
        this.carapaceProtocols = carapaceProtocols.stream().map(HttpProtocol::name).collect(toUnmodifiableSet());
        this.withCache = withCache;
    }

    @Parameters(name = "Client: {0}, Carapace conf: {1}, using cache: {2}")
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
    public void test() throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf(RESPONSE.length()))
                        .withBody(RESPONSE))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port(), withCache);
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
                    carapaceProtocols
            ));

            server.start();
            final var port = server.getLocalPort();
            assertThat(executeRequest(port), is(RESPONSE));
            if (withCache) {
                assertThat(executeRequest(port), is(RESPONSE));
            }
        }
    }

    private String executeRequest(final int port) {
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
