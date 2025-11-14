package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_COUNT;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_IDLE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_INTERVAL;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_MAX_KEEP_ALIVE_REQUESTS;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SO_BACKLOG;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.HttpUtils;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Test for HTTP protocol and SSL combinations with ALPN negotiation.
 * <p>
 * This test verifies that Carapace Proxy correctly handles combinations of:
 * <ul>
 *      <li>Protocol: HTTP/1.1 vs HTTP/2</li>
 *      <li>Connection Security: HTTPS (TLS) vs cleartext HTTP</li>
 * </ul>
 * <p>
 * The test ensures that the same HTTP protocol version is used for both client-to-proxy
 * and proxy-to-backend communications.
 */
@RunWith(Parameterized.class)
public class HttpProtocolAndSSLTest {

    private static final String RESPONSE = "Response from backend";

    private final boolean clientUsesHttps;
    private final boolean backendUsesHttps;
    private final HttpVersion httpVersion;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    public HttpProtocolAndSSLTest(boolean clientUsesHttps, boolean backendUsesHttps, HttpVersion httpVersion) {
        this.clientUsesHttps = clientUsesHttps;
        this.backendUsesHttps = backendUsesHttps;
        this.httpVersion = httpVersion;
    }

    @Parameters(name = "Client HTTPS: {0}, Backend HTTPS: {1}, Protocol Version: {2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                /* HTTP/1.1 protocol (all SSL combinations) */

                // HTTP client to HTTP backend
                {false, false, HttpVersion.HTTP_1_1},
                // HTTP client to HTTPS backend
                {false, true, HttpVersion.HTTP_1_1},
                // HTTPS client to HTTP backend
                {true, false, HttpVersion.HTTP_1_1},
                // HTTPS client to HTTPS backend
                {true, true, HttpVersion.HTTP_1_1},

                /* HTTP/2 protocol (valid SSL combinations) */

                // HTTP client to HTTP backend (H2C)
                {false, false, HttpVersion.valueOf("HTTP/2.0")},
                // HTTPS client to HTTPS backend (H2)
                {true, true, HttpVersion.valueOf("HTTP/2.0")}
        });
    }

    @Test
    public void testHttpProtocolsAndSSL() throws Exception {
        HttpTestUtils.overrideJvmWideHttpsVerifier();
        final String carapaceCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        final String backendCertificate = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        final HttpProtocol clientHttpProtocol = HttpUtils.getAppropriateProtocol(httpVersion, clientUsesHttps);
        final HttpProtocol backendHttpProtocol = HttpUtils.getAppropriateProtocol(httpVersion, backendUsesHttps);

        WireMockConfiguration config = options()
                .dynamicPort()
                .dynamicHttpsPort()
                .http2PlainDisabled(backendHttpProtocol != HttpProtocol.H2C);
        if (backendUsesHttps) {
            config = config
                    .keystorePath(backendCertificate)
                    .keystoreType("PKCS12")
                    .keystorePassword("changeit")
                    .keyManagerPassword("changeit")
                    .http2TlsDisabled(backendHttpProtocol != HttpProtocol.H2);
        }
        final WireMockRule backend = new WireMockRule(config);

        try {
            final int httpPort = TestUtils.getFreePort();
            final int httpsPort = TestUtils.getFreePort();
            final String path = "/index.html";
            backend.start();
            backend.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody(RESPONSE)));
            final int backendPort = backendUsesHttps ? backend.httpsPort() : backend.port();
            final BackendConfiguration backendConfig = backendUsesHttps
                    ? new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, true, backendCertificate, "changeit")
                    : new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, false);
            final TestEndpointMapper mapper = new TestEndpointMapper(backendConfig, false) {
                @Override
                public MapResult map(final ProxyRequest request) {
                    // Always return STABLE to bypass capacity check
                    final BackendHealthStatus healthStatus = new BackendHealthStatus(request.getListener(), 0) {
                        @Override
                        public Status getStatus() {
                            return Status.STABLE;
                        }
                    };

                    return MapResult.builder()
                            .host("localhost")
                            .port(backendPort)
                            .action(MapResult.Action.PROXY)
                            .routeId("test-route")
                            .healthStatus(healthStatus)
                            .ssl(backendUsesHttps)
                            .build();
                }
            };
            try (final HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
                server.addCertificate(new SSLCertificateConfiguration("localhost", null, carapaceCertificate, "changeit", STATIC));

                final Set<HttpProtocol> httpProtocols = new HashSet<>();
                httpProtocols.add(HttpProtocol.HTTP11);
                if (!clientUsesHttps && (clientHttpProtocol == HttpProtocol.H2C)) {
                    httpProtocols.add(HttpProtocol.H2C);
                }
                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpPort, false, null, null,
                        DEFAULT_SSL_PROTOCOLS, DEFAULT_SO_BACKLOG, DEFAULT_KEEP_ALIVE,
                        DEFAULT_KEEP_ALIVE_IDLE, DEFAULT_KEEP_ALIVE_INTERVAL, DEFAULT_KEEP_ALIVE_COUNT,
                        DEFAULT_MAX_KEEP_ALIVE_REQUESTS, DEFAULT_FORWARDED_STRATEGY, Set.of(),
                        httpProtocols, new DefaultChannelGroup(new DefaultEventExecutor())));
                
                // Configure HTTPS listener
                final Set<HttpProtocol> httpsProtocols = new HashSet<>();
                httpsProtocols.add(HttpProtocol.HTTP11);
                if (clientUsesHttps && clientHttpProtocol == HttpProtocol.H2) {
                    httpsProtocols.add(HttpProtocol.H2);
                }
                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpsPort, true, null, "localhost",
                        DEFAULT_SSL_PROTOCOLS, DEFAULT_SO_BACKLOG, DEFAULT_KEEP_ALIVE,
                        DEFAULT_KEEP_ALIVE_IDLE, DEFAULT_KEEP_ALIVE_INTERVAL, DEFAULT_KEEP_ALIVE_COUNT,
                        DEFAULT_MAX_KEEP_ALIVE_REQUESTS, DEFAULT_FORWARDED_STRATEGY, Set.of(),
                        httpsProtocols, new DefaultChannelGroup(new DefaultEventExecutor())));
                
                server.start();
                final int port = clientUsesHttps ? httpsPort : httpPort;
                final String protocol = clientUsesHttps ? "https" : "http";

                HttpClient client = HttpClient.create().protocol(clientHttpProtocol);
                if (clientUsesHttps) {
                    final SslContext sslContext = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .applicationProtocolConfig(new ApplicationProtocolConfig(
                                    ApplicationProtocolConfig.Protocol.ALPN,
                                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                    ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                            .build();
                    client = client.secure(spec -> spec.sslContext(sslContext));
                }

                final HttpClientResponse response = client
                        .get()
                        .uri(protocol + "://localhost:" + port + path)
                        .response()
                        .block();
                assertNotNull("Response should not be null", response);
                assertEquals("Status should be 200 OK", HttpResponseStatus.OK, response.status());
                
                System.out.println("Test scenario: Client HTTPS: " + clientUsesHttps +
                        ", Backend HTTPS: " + backendUsesHttps +
                        ", Protocol Version: " + httpVersion);
                System.out.println("Response protocol: " + response.version().text());

                if (httpVersion.majorVersion() == 2) {
                    assertEquals("Protocol should be HTTP/2", "HTTP/2.0", response.version().text());
                } else {
                    assertEquals("Protocol should be HTTP/1.1", "HTTP/1.1", response.version().text());
                }
                
                final String responseBody = client
                        .get()
                        .uri(protocol + "://localhost:" + port + path)
                        .responseContent()
                        .aggregate()
                        .asString()
                        .block();
                assertEquals("Response body should match", RESPONSE, responseBody);
            }
        } finally {
            backend.stop();
        }
    }
}
