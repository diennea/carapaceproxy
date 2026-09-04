package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.KeyStoreRule;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

public class HttpProtocolAndSSLIT {

    private static final String RESPONSE = "Response from backend";
    private static final String BIG_PAYLOAD = "X".repeat(500_000);

    @TempDir
    public static File temporaryFolder;
    @RegisterExtension
    public static KeyStoreRule carapaceKeyStore = new KeyStoreRule();
    @RegisterExtension
    public static KeyStoreRule backendKeyStore = new KeyStoreRule();

    public static Collection<Object[]> scenarios() {
        final Set<EnumSet<HttpProtocol>> clearTextOptions = Set.of(
                EnumSet.of(HttpProtocol.HTTP11),
                EnumSet.of(HttpProtocol.H2C),
                EnumSet.of(HttpProtocol.HTTP11, HttpProtocol.H2C)
        );
        final Set<EnumSet<HttpProtocol>> encryptedOptions = Set.of(
                EnumSet.of(HttpProtocol.HTTP11),
                EnumSet.of(HttpProtocol.H2),
                EnumSet.of(HttpProtocol.HTTP11, HttpProtocol.H2)
        );
        final Collection<Scenario> scenarios = new ArrayList<>();
        for (final EnumSet<HttpProtocol> clientProtocols : clearTextOptions) {
            for (final EnumSet<HttpProtocol> listenerProtocols : clearTextOptions) {
                if (clientProtocols.contains(HttpProtocol.HTTP11)
                        && clientProtocols.contains(HttpProtocol.H2C)
                        && listenerProtocols.equals(EnumSet.of(HttpProtocol.H2C))) {
                    // According to RFC 9113 §3 and RFC 7540 §3.2,
                    //  this scenario must result in an Upgrade request over HTTP/1.1,
                    //  because the client should not execute cleartext HTTP/2 requests
                    //  without out-of-band prior knowledge.
                    //
                    // This test already verifies prior-knowledge-H2c and upgrade-over-http/1.1 tests scenarios,
                    //  so we should skip the `H1+H2c → H2c → *` scenarios.
                    continue;
                }
                if (Collections.disjoint(clientProtocols, listenerProtocols)) {
                    continue;
                }
                for (final EnumSet<HttpProtocol> backendProtocols : clearTextOptions) {
                    scenarios.add(new Scenario(clientProtocols, listenerProtocols, backendProtocols, false, false));
                }
                for (final EnumSet<HttpProtocol> backendProtocols : encryptedOptions) {
                    scenarios.add(new Scenario(clientProtocols, listenerProtocols, backendProtocols, false, true));
                }
            }
        }
        for (final EnumSet<HttpProtocol> clientProtocols : encryptedOptions) {
            for (final EnumSet<HttpProtocol> listenerProtocols : encryptedOptions) {
                if (Collections.disjoint(clientProtocols, listenerProtocols)) {
                    continue;
                }
                for (final EnumSet<HttpProtocol> backendProtocols : clearTextOptions) {
                    scenarios.add(new Scenario(clientProtocols, listenerProtocols, backendProtocols, true, false));
                }
                for (final EnumSet<HttpProtocol> backendProtocols : encryptedOptions) {
                    scenarios.add(new Scenario(clientProtocols, listenerProtocols, backendProtocols, true, true));
                }
            }
        }
        return scenarios.stream().map(s -> new Object[]{s}).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void matrixCase(final Scenario scenario) throws Exception {
        final AtomicReference<String> channelIdRef = new AtomicReference<>();
        final AtomicBoolean allSameConnection = new AtomicBoolean(true);
        final WireMockConfiguration cfg = WireMockConfiguration.options();
        if (scenario.proxyToBackendTls) {
            cfg.dynamicHttpsPort()
                    .keystoreType(backendKeyStore.getKeyStore().getType())
                    .keystorePath(backendKeyStore.getKeyStoreFile().getAbsolutePath())
                    .keystorePassword(new String(backendKeyStore.getKeyStorePassword()))
                    .httpDisabled(true)
                    .http2TlsDisabled(scenario.backendHttp11());
        } else {
            cfg.dynamicPort()
                    .httpDisabled(false)
                    .http2PlainDisabled(scenario.backendHttp11());
        }
        final WireMockServer backendRule = new WireMockServer(cfg);
        backendRule.start();

        backendRule.stubFor(get(urlEqualTo("/tomcatstatus/up"))
                .willReturn(okJson("{ \"status\": \"UP\", \"checks\": [] }")));

        backendRule.stubFor(get(urlEqualTo("/index.html"))
                .willReturn(okForContentType("text/html", RESPONSE)));

        backendRule.stubFor(get(urlPathMatching("/lorem/.*\\.js"))
                .willReturn(okForContentType("application/javascript; charset=UTF-8",
                        "(function(){\n"
                                + "  // big JS payload\n"
                                + "  var data = \"" + BIG_PAYLOAD + "\";\n"
                                + "  if (window && window.console) { console.log(data.length); }\n"
                                + "})();\n")));

        backendRule.stubFor(get(urlPathMatching("/assets/.*\\.css"))
                .willReturn(okForContentType("text/css; charset=UTF-8",
                        "/* big CSS payload */\n"
                                + ".lorem-css { content: \"" + BIG_PAYLOAD + "\"; }\n")));

        final int backendPort = scenario.proxyToBackendTls ? backendRule.httpsPort() : backendRule.port();
        final int carapaceProxyPort = TestUtils.getFreePort();

        final TestEndpointMapper endpointMapper = getMapper(scenario, backendPort);
        try (final HttpProxyServer server = new HttpProxyServer(endpointMapper, newFolder(temporaryFolder, "junit"))) {
            final NetworkListenerConfiguration listenerConfiguration;
            if (scenario.clientToProxyTls) {
                final SSLCertificateConfiguration certificateConfiguration = new SSLCertificateConfiguration(
                        "localhost",
                        null,
                        carapaceKeyStore.getKeyStoreFile().getAbsolutePath(),
                        new String(carapaceKeyStore.getKeyStorePassword()),
                        SSLCertificateConfiguration.CertificateMode.STATIC
                );
                server.addCertificate(certificateConfiguration);
                listenerConfiguration = NetworkListenerConfiguration.withDefaultSsl(
                        "localhost",
                        carapaceProxyPort,
                        certificateConfiguration.getId(),
                        scenario.proxyProtocols
                );
            } else {
                listenerConfiguration = NetworkListenerConfiguration.withoutSsl(
                        "localhost",
                        carapaceProxyPort,
                        scenario.proxyProtocols
                );
            }
            server.addListener(listenerConfiguration);
            server.start();

            final HttpClient client = buildClient(scenario, carapaceProxyPort, channelIdRef, allSameConnection);

            final String scheme = scenario.clientToProxyTls ? "https" : "http";
            final String baseUri = scheme + "://localhost:" + carapaceProxyPort;

            final String htmlBody = client
                    .get()
                    .uri(baseUri + "/index.html")
                    .responseContent()
                    .aggregate()
                    .asString()
                    .block();
            assertThat(htmlBody).isEqualTo(RESPONSE);

            final int jsFiles = 5;
            final int cssFiles = 5;
            final Flux<String> jsRequests = Flux.range(1, jsFiles)
                    .flatMap(i -> client
                            .get()
                            .uri(baseUri + "/lorem/" + i + ".js")
                            .responseContent()
                            .aggregate()
                            .asString());
            final Flux<String> cssRequests = Flux.range(1, cssFiles)
                    .flatMap(i -> client
                            .get()
                            .uri(baseUri + "/assets/" + i + ".css")
                            .responseContent()
                            .aggregate()
                            .asString());

            final List<String> allBodies = Flux.merge(jsRequests, cssRequests)
                    .collectList()
                    .block();

            assertThat(allBodies)
                    .hasSize(jsFiles + cssFiles);
            for (String body : allBodies) {
                assertThat(body).as("Body too small").hasSizeGreaterThan(10_000);
            }
            if (scenario.proxyHttp2()) {
                assertThat(allSameConnection.get()).as("HTTP/2 requests must be multiplexed over a single TCP connection " + channelIdRef.get()).isTrue();
            }
        } finally {
            backendRule.stop();
        }
    }

    private TestEndpointMapper getMapper(final Scenario scenario, final int backendPort) {
        final BackendConfiguration backendConfiguration = new BackendConfiguration(
                "wiremock",
                new EndpointKey("localhost", backendPort),
                "/tomcatstatus/up",
                0,
                scenario.proxyToBackendTls,
                scenario.proxyToBackendTls ? backendKeyStore.getTrustStoreFile().getAbsolutePath() : null,
                scenario.proxyToBackendTls ? new String(backendKeyStore.getKeyStorePassword()) : null,
                scenario.proxyToBackendTls ? "https" : "http"
        );
        return new TestEndpointMapper(backendConfiguration, false);
    }

    private HttpClient buildClient(final Scenario scenario, final int proxyPort, final AtomicReference<String> channelIdRef, final AtomicBoolean allSameConnection) throws Exception {
        final ConnectionProvider provider = ConnectionProvider.builder("client-single-conn")
                .maxConnections(1)
                .pendingAcquireMaxCount(100)
                .build();
        HttpClient client = HttpClient.create(provider);
        if (scenario.clientToProxyTls) {
            final SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(carapaceKeyStore.getTrustManagerFactory())
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            toAlpnProtocols(scenario.clientProtocols)))
                    .build();
            client = client.secure(spec -> spec.sslContext(sslContext));
        }
        if (scenario.proxyHttp2()) {
            client = client.doOnConnected(connection -> {
                final String id = connection.channel().id().asShortText();
                final String existing = channelIdRef.updateAndGet(ref -> ref == null ? id : ref);
                if (!existing.equals(id)) {
                    allSameConnection.set(false);
                }
            });
        }
        return client
                .protocol(scenario.clientProtocols.toArray(HttpProtocol[]::new))
                .baseUrl((scenario.clientToProxyTls ? "https" : "http") + "://localhost:" + proxyPort);
    }

    private String[] toAlpnProtocols(final EnumSet<HttpProtocol> protocols) {
        return protocols.stream().map(protocol -> switch (protocol) {
            case HTTP11 -> ApplicationProtocolNames.HTTP_1_1;
            case H2C, H2 -> ApplicationProtocolNames.HTTP_2;
            case HTTP3 -> "h3";
        }).toArray(String[]::new);
    }

    public record Scenario(
            EnumSet<HttpProtocol> clientProtocols,
            EnumSet<HttpProtocol> proxyProtocols,
            EnumSet<HttpProtocol> backendProtocols,
            boolean clientToProxyTls,
            boolean proxyToBackendTls
    ) {
        @Override
        public String toString() {
            return "Client:" + clientProtocols
                    + " →[" + (clientToProxyTls ? "HTTPS" : "HTTP") + "]"
                    + " Carapace:" + proxyProtocols
                    + " →[" + (proxyToBackendTls ? "HTTPS" : "HTTP") + "]"
                    + " Backend:" + backendProtocols;
        }

        public boolean backendHttp11() {
            return !(proxyToBackendTls
                    ? backendProtocols.contains(HttpProtocol.H2)
                    : backendProtocols.contains(HttpProtocol.H2C));
        }

        public boolean proxyHttp2() {
            return clientToProxyTls
                    ? proxyProtocols.contains(HttpProtocol.H2)
                    : proxyProtocols.contains(HttpProtocol.H2C);
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs) + "-" + System.nanoTime();
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
