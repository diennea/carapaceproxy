package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
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
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.server.HttpServer;

public class Http2HeadersTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private NetworkListenerConfiguration newH2CListenerConfiguration() {
        return new NetworkListenerConfiguration(
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
                Set.of(HttpProtocol.H2C, HttpProtocol.HTTP11),
                new DefaultChannelGroup(new DefaultEventExecutor()));
    }

    /**
     * Regression test for the original H2C upgrade scenario: a client sending Connection/Upgrade
     * headers should still get a successful response via Carapace.
     */
    @Test
    public void testH2CUpgradeRequestSucceeds() throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!"))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        final HttpClientResponse response;
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(newH2CListenerConfiguration());
            server.start();
            final var port = server.getLocalPort();
            response = HttpClient.create()
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
        }
        assertThat(response, is(notNullValue()));
        assertThat(response.status(), is(HttpResponseStatus.OK));
        assertThat(response.version(), is(HttpVersion.valueOf("HTTP/2.0")));
    }

    /**
     * Hop-by-hop headers sent by the client must not be forwarded to the backend.
     * A reverse proxy must strip them before forwarding (RFC 2616 §13.5.1, RFC 7230 §6.1).
     * Note: Reactor Netty manages its own Connection header for the backend connection (e.g. for
     * H2C upgrade), so we verify the absence of the specific client-originated hop-by-hop headers.
     */
    @Test
    public void testHopByHopHeadersStrippedFromRequest() throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/plain")
                        .withBody("ok"))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(newH2CListenerConfiguration());
            server.start();
            final var port = server.getLocalPort();
            HttpClient.create()
                    .protocol(HttpProtocol.HTTP11)
                    .headers(headers -> headers
                            .add(HttpHeaderNames.CONNECTION, "keep-alive")
                            .add(HttpHeaderNames.KEEP_ALIVE, "timeout=5, max=100")
                    )
                    .get()
                    .uri("http://localhost:" + port + "/index.html")
                    .response()
                    .block();
        }
        // Verify the backend did not receive the client's hop-by-hop headers.
        // Connection is excluded from this check because Reactor Netty adds its own value
        // when initiating a backend connection (e.g. "HTTP2-Settings, upgrade" for H2C upgrade).
        verify(getRequestedFor(urlEqualTo("/index.html"))
                .withoutHeader(HttpHeaderNames.KEEP_ALIVE.toString())
        );
    }

    /**
     * Hop-by-hop headers in the backend response must not be forwarded to the client.
     * A reverse proxy must strip them before sending the response (RFC 2616 §13.5.1, RFC 7230 §6.1).
     * This also ensures HTTP/2 compliance on the client-facing connection (RFC 9113 §8.2.2).
     * Note: Reactor Netty's HTTP server layer manages the Connection header for the client-facing
     * connection autonomously, so we verify the absence of Keep-Alive (which Reactor Netty never sets).
     */
    @Test
    public void testHopByHopHeadersStrippedFromResponse() throws IOException, ConfigurationNotValidException, InterruptedException {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(HttpResponseStatus.OK.code())
                        .withHeader("Content-Type", "text/plain")
                        .withHeader("Connection", "keep-alive")
                        .withHeader("Keep-Alive", "timeout=5, max=100")
                        .withBody("ok"))
        );
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        final HttpClientResponse response;
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(newH2CListenerConfiguration());
            server.start();
            final var port = server.getLocalPort();
            response = HttpClient.create()
                    .protocol(HttpProtocol.HTTP11)
                    .get()
                    .uri("http://localhost:" + port + "/index.html")
                    .response()
                    .block();
        }
        assertThat(response, is(notNullValue()));
        assertThat(response.status(), is(HttpResponseStatus.OK));
        // Keep-Alive must be stripped from the backend response before forwarding to the client.
        // Connection is excluded from this check because Reactor Netty's HTTP server manages it
        // autonomously for the client-facing connection.
        assertThat(response.responseHeaders().get(HttpHeaderNames.KEEP_ALIVE), is(nullValue()));
    }

    /**
     * Anchors the original PR symptom: an HTTP/1.x client sending hop-by-hop headers through Carapace
     * to an HTTP/2-capable backend. Without the strip, Carapace would forward the client's
     * {@code Connection}/{@code Keep-Alive} verbatim onto an H2C-upgraded backend connection, where
     * Netty's strict H2 encoder rejects connection-specific headers (RFC 9113 §8.2.2) and the
     * request fails with a {@code StreamException}. Uses a Reactor Netty {@code HttpServer} configured
     * for H2C as the backend so Carapace's outbound {@code HttpClient} can negotiate H2.
     */
    @Test
    public void testHopByHopHeadersStrippedForH2cBackend() throws IOException, ConfigurationNotValidException, InterruptedException {
        final AtomicReference<HttpHeaders> capturedRequestHeaders = new AtomicReference<>();
        final DisposableServer h2cBackend = HttpServer.create()
                .host("localhost")
                .port(0)
                .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
                .route(routes -> routes.get("/index.html", (req, resp) -> {
                    capturedRequestHeaders.set(req.requestHeaders().copy());
                    return resp.status(HttpResponseStatus.OK)
                            .sendString(Mono.just("ok"));
                }))
                .bindNow();
        try {
            final var mapper = new TestEndpointMapper("localhost", h2cBackend.port());
            final HttpClientResponse response;
            try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
                server.addListener(newH2CListenerConfiguration());
                server.start();
                final var port = server.getLocalPort();
                response = HttpClient.create()
                        .protocol(HttpProtocol.HTTP11)
                        .headers(headers -> headers
                                .add(HttpHeaderNames.CONNECTION, "keep-alive")
                                .add(HttpHeaderNames.KEEP_ALIVE, "timeout=5, max=100")
                        )
                        .get()
                        .uri("http://localhost:" + port + "/index.html")
                        .response()
                        .block();
            }
            // The request itself must succeed: pre-fix this would 503 once the connection switches to H2
            // and Netty's H2 encoder rejects the forwarded Keep-Alive header.
            assertThat(response, is(notNullValue()));
            assertThat(response.status(), is(HttpResponseStatus.OK));
            // And the backend must never see Keep-Alive — regardless of which protocol the proxy used
            // to talk to it.
            assertThat(capturedRequestHeaders.get(), is(notNullValue()));
            assertThat(capturedRequestHeaders.get().get(HttpHeaderNames.KEEP_ALIVE), is(nullValue()));
        } finally {
            h2cBackend.disposeNow();
        }
    }
}
