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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

public class Http2Test {

    public static final String RESPONSE = "it <b>works</b> !!";

    @RegisterExtension
    public static WireMockExtension wireMockRule = WireMockExtension.newInstance().options(options().dynamicPort()).build();

    @TempDir
    File tmpDir;

    private HttpProtocol protocol;

    public void initHttp2Test(final HttpProtocol protocol, final Set<HttpProtocol> carapaceProtocols, final boolean withCache) {
        this.protocol = protocol;
        carapaceProtocols.stream().map(HttpProtocol::name).collect(toUnmodifiableSet());
    }

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

    @MethodSource("data")
    @ParameterizedTest(name = "Client: {0}, Carapace conf: {1}, using cache: {2}")
    public void test(final HttpProtocol protocol, final Set<HttpProtocol> carapaceProtocols, final boolean withCache) throws IOException, ConfigurationNotValidException, InterruptedException {
        initHttp2Test(protocol, carapaceProtocols, withCache);
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf(RESPONSE.length()))
                        .withBody(RESPONSE))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.getPort(), withCache);
        try (final var server = new HttpProxyServer(mapper, newFolder(tmpDir, "junit"))) {
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
                    carapaceProtocols.stream().map(HttpProtocol::toString).collect(Collectors.toUnmodifiableSet())
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

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
