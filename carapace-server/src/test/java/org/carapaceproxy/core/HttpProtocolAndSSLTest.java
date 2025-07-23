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
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.server.backends.BackendHealthStatus;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.mapper.MapResult;
import org.carapaceproxy.utils.HttpTestUtils;
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
 * Comprehensive test for HTTP protocol and SSL combinations with ALPN negotiation.
 * <p>
 * This test implements a complete matrix approach to verify that Carapace Proxy correctly handles
 * all combinations of:
 * <ul>
 *      <li>Protocol: HTTP/1.1 vs HTTP/2 (H2 and H2C)</li>
 *      <li>Connection Security: HTTPS (TLS) vs cleartext HTTP</li>
 *      <li>Connection Segment: Client-to-Carapace and Carapace-to-Backend</li>
 * </ul>
 * <p>
 * The test matrix covers the following combinations:
 * <ol>
 *      <li>HTTP/1.1 client to HTTP/1.1 backend (all SSL combinations)
 *          <ul>
 *              <li>HTTP client to HTTP backend</li>
 *              <li>HTTP client to HTTPS backend</li>
 *              <li>HTTPS client to HTTP backend</li>
 *              <li>HTTPS client to HTTPS backend</li>
 *          </ul>
 *      </li>
 *      <li>HTTP/1.1 client to HTTP/2 backend (all combinations)
 *          <ul>
 *              <li>HTTP client to H2C backend</li>
 *              <li>HTTP client to H2 backend</li>
 *              <li>HTTPS client to H2C backend</li>
 *              <li>HTTPS client to H2 backend</li>
 *          </ul>
 *      </li>
 *      <li>HTTP/2 client to HTTP/1.1 backend (all combinations)
 *          <ul>
 *              <li>H2C client to HTTP backend</li>
 *              <li>H2C client to HTTPS backend</li>
 *              <li>H2 client to HTTP backend</li>
 *              <li>H2 client to HTTPS backend</li>
 *          </ul>
 *      </li>
 *      <li>HTTP/2 client to HTTP/2 backend (all combinations)
 *          <ul>
 *              <li>H2C client to H2C backend</li>
 *              <li>H2C client to H2 backend</li>
 *              <li>H2 client to H2C backend</li>
 *              <li>H2 client to H2 backend</li>
 *          </ul>
 *      </li>
 * </ol>
 * <p>
 * This test leverages WireMock's native HTTP/2 support with proper ALPN negotiation for both
 * secure (H2) and cleartext (H2C) connections. The test verifies that Carapace correctly handles
 * protocol negotiation and proxying between different protocols and security configurations.
 */
@RunWith(Parameterized.class)
public class HttpProtocolAndSSLTest {

    private static final String RESPONSE = "Response from backend";

    private final boolean clientUsesHttps;
    private final boolean backendUsesHttps;
    private final HttpProtocol clientProtocol;
    private final HttpProtocol backendProtocol;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Rule
    public WireMockRule backend;

    public HttpProtocolAndSSLTest(boolean clientUsesHttps, boolean backendUsesHttps,
                                  HttpProtocol clientProtocol, HttpProtocol backendProtocol) {
        this.clientUsesHttps = clientUsesHttps;
        this.backendUsesHttps = backendUsesHttps;
        this.clientProtocol = clientProtocol;
        this.backendProtocol = backendProtocol;
    }

    @Parameters(name = "Client HTTPS: {0}, Backend HTTPS: {1}, Client Protocol: {2}, Backend Protocol: {3}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                // 1. HTTP/1.1 client to HTTP/1.1 backend (all SSL combinations)
                {false, false, HttpProtocol.HTTP11, HttpProtocol.HTTP11}, // HTTP client to HTTP backend
                {false, true, HttpProtocol.HTTP11, HttpProtocol.HTTP11},  // HTTP client to HTTPS backend
                {true, false, HttpProtocol.HTTP11, HttpProtocol.HTTP11},  // HTTPS client to HTTP backend
                {true, true, HttpProtocol.HTTP11, HttpProtocol.HTTP11},   // HTTPS client to HTTPS backend
                
                // 2. HTTP/1.1 client to HTTP/2 backend (all combinations)
                {false, false, HttpProtocol.HTTP11, HttpProtocol.H2C},    // HTTP client to H2C backend
                {false, true, HttpProtocol.HTTP11, HttpProtocol.H2},      // HTTP client to H2 backend
                {true, false, HttpProtocol.HTTP11, HttpProtocol.H2C},     // HTTPS client to H2C backend
                {true, true, HttpProtocol.HTTP11, HttpProtocol.H2},       // HTTPS client to H2 backend
                
                // 3. HTTP/2 client to HTTP/1.1 backend (all combinations)
                {false, false, HttpProtocol.H2C, HttpProtocol.HTTP11},    // H2C client to HTTP backend
                {false, true, HttpProtocol.H2C, HttpProtocol.HTTP11},     // H2C client to HTTPS backend
                {true, false, HttpProtocol.H2, HttpProtocol.HTTP11},      // H2 client to HTTP backend
                {true, true, HttpProtocol.H2, HttpProtocol.HTTP11},       // H2 client to HTTPS backend
                
                // 4. HTTP/2 client to HTTP/2 backend (all combinations)
                {false, false, HttpProtocol.H2C, HttpProtocol.H2C},       // H2C client to H2C backend
                {false, true, HttpProtocol.H2C, HttpProtocol.H2},         // H2C client to H2 backend
                {true, false, HttpProtocol.H2, HttpProtocol.H2C},         // H2 client to H2C backend
                {true, true, HttpProtocol.H2, HttpProtocol.H2}            // H2 client to H2 backend
        });
    }

    @Test
    public void testHttpProtocolsAndSSL() throws Exception {
        // Override JVM-wide HTTPS verifier for testing
        HttpTestUtils.overrideJvmWideHttpsVerifier();

        // Deploy certificates - use the same certificates as SSLBackendTest
        String carapaceCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        String backendCertificate = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        // Configure and start WireMock with the appropriate certificate and HTTP/2 support
        WireMockConfiguration config = options()
                .dynamicPort()
                .dynamicHttpsPort()
                .http2PlainDisabled(backendProtocol != HttpProtocol.H2C);

        // Configure SSL if needed
        if (backendUsesHttps) {
            config = config
                    .keystorePath(backendCertificate)
                    .keystoreType("PKCS12")
                    .keystorePassword("changeit")
                    .keyManagerPassword("changeit")
                    .http2TlsDisabled(backendProtocol != HttpProtocol.H2);
        }

        backend = new WireMockRule(config);

        try {
            // Get free ports for HTTP and HTTPS
            final int httpPort = TestUtils.getFreePort();
            final int httpsPort = TestUtils.getFreePort();

            // Setup test data
            final String path = "/index.html";

            // Start backend and ensure it's running
            backend.start();

            // Setup backend stub - must be done after backend is started
            backend.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody(RESPONSE)));

            // Create backend configuration
            final int backendPort = backendUsesHttps ? backend.httpsPort() : backend.port();
            final BackendConfiguration backendConfig = backendUsesHttps
                    ? new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, true, backendCertificate, "changeit")
                    : new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, false);

            // Setup test endpoint mapper with custom backend configuration
            Map<String, BackendConfiguration> backends = new HashMap<>();
            backends.put("test-backend", backendConfig);
            TestEndpointMapper mapper = new TestEndpointMapper("localhost", backendPort, false, backends) {
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
                            .backendProtocol(backendProtocol)
                            .build();
                }
            };

            // Create and configure HTTP proxy server
            try (final HttpProxyServer server = new HttpProxyServer(mapper, tmpDir.getRoot())) {
                server.addCertificate(new SSLCertificateConfiguration("localhost", null, carapaceCertificate, "changeit", STATIC));

                // Add HTTP listener
                Set<HttpProtocol> httpProtocols = new HashSet<>();
                httpProtocols.add(HttpProtocol.HTTP11);
                if (!clientUsesHttps && (clientProtocol == HttpProtocol.H2C)) {
                    httpProtocols.add(HttpProtocol.H2C);
                }

                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpPort, false, null, null,
                        DEFAULT_SSL_PROTOCOLS, DEFAULT_SO_BACKLOG, DEFAULT_KEEP_ALIVE,
                        DEFAULT_KEEP_ALIVE_IDLE, DEFAULT_KEEP_ALIVE_INTERVAL, DEFAULT_KEEP_ALIVE_COUNT,
                        DEFAULT_MAX_KEEP_ALIVE_REQUESTS, DEFAULT_FORWARDED_STRATEGY, Set.of(),
                        httpProtocols, new DefaultChannelGroup(new DefaultEventExecutor())));

                // Add HTTPS listener
                Set<HttpProtocol> httpsProtocols = new HashSet<>();
                httpsProtocols.add(HttpProtocol.HTTP11);
                if (clientUsesHttps && clientProtocol == HttpProtocol.H2) {
                    httpsProtocols.add(HttpProtocol.H2);
                }

                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpsPort, true, null, "localhost",
                        DEFAULT_SSL_PROTOCOLS, DEFAULT_SO_BACKLOG, DEFAULT_KEEP_ALIVE,
                        DEFAULT_KEEP_ALIVE_IDLE, DEFAULT_KEEP_ALIVE_INTERVAL, DEFAULT_KEEP_ALIVE_COUNT,
                        DEFAULT_MAX_KEEP_ALIVE_REQUESTS, DEFAULT_FORWARDED_STRATEGY, Set.of(),
                        httpsProtocols, new DefaultChannelGroup(new DefaultEventExecutor())));

                server.start();

                // Select the appropriate port based on whether client uses HTTPS
                final int port = clientUsesHttps ? httpsPort : httpPort;
                final String protocol = clientUsesHttps ? "https" : "http";

                // Create HTTP client with appropriate protocol
                HttpClient client = HttpClient.create()
                        .protocol(clientProtocol);

                if (clientUsesHttps) {
                    // Configure SSL for client
                    SslContext sslContext = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .applicationProtocolConfig(new ApplicationProtocolConfig(
                                    ApplicationProtocolConfig.Protocol.ALPN,
                                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                    ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1))
                            .build();

                    client = client.secure(spec -> spec.sslContext(sslContext));
                }

                // Execute request
                HttpClientResponse response = client
                        .get()
                        .uri(protocol + "://localhost:" + port + path)
                        .response()
                        .block();

                // Verify response
                assertNotNull("Response should not be null", response);
                assertEquals("Status should be 200 OK", HttpResponseStatus.OK, response.status());

                // Verify protocol version
                System.out.println("Test scenario: Client HTTPS: " + clientUsesHttps +
                        ", Backend HTTPS: " + backendUsesHttps +
                        ", Client Protocol: " + clientProtocol +
                        ", Backend Protocol: " + backendProtocol);
                System.out.println("Response protocol: " + response.version().text());

                if (clientProtocol == HttpProtocol.H2 || clientProtocol == HttpProtocol.H2C) {
                    assertEquals("Protocol should be HTTP/2", "HTTP/2.0", response.version().text());
                } else {
                    assertEquals("Protocol should be HTTP/1.1", "HTTP/1.1", response.version().text());
                }

                // Note: We can't directly verify the backend protocol used, but we've explicitly set it in the mapper
                // and the ProxyRequestsManager should use it as configured

                // Verify response content
                String responseBody = client
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
