package org.carapaceproxy.backends;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_FORWARDED_STRATEGY;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertEquals;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.mapper.EndpointMapper;
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

@RunWith(Parameterized.class)
public class SSLBackendTest {

    private final boolean clientUsesHttps;
    private final boolean backendUsesHttps;
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    public SSLBackendTest(boolean clientUsesHttps, boolean backendUsesHttps) {
        this.clientUsesHttps = clientUsesHttps;
        this.backendUsesHttps = backendUsesHttps;
    }

    @Parameters(name = "Client HTTPS: {0}, Backend HTTPS: {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {false, false}, // HTTP client to HTTP backend
                {false, true},  // HTTP client to HTTPS backend
                {true, false},  // HTTPS client to HTTP backend
                {true, true}    // HTTPS client to HTTPS backend
        });
    }

    @Test
    public void testHttpAndHttpsBackends() throws Exception {
        HttpTestUtils.overrideJvmWideHttpsVerifier();
        final String carapaceCertificate = TestUtils.deployResource("ia.p12", tmpDir.getRoot());
        final String backendCertificate = TestUtils.deployResource("ca.p12", tmpDir.getRoot());

        WireMockConfiguration config = options()
                .dynamicPort()
                .dynamicHttpsPort();

        if (backendUsesHttps) {
            config = config
                    .keystorePath(backendCertificate)
                    .keystoreType("PKCS12")
                    .keystorePassword("changeit")
                    .keyManagerPassword("changeit");
        }

        final WireMockRule backend = new WireMockRule(config);

        try {
            final int httpPort = TestUtils.getFreePort();
            final int httpsPort = TestUtils.getFreePort();
            final String path = "/test";
            final String expectedResponse = "Response from backend";
            backend.start();

            backend.stubFor(get(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody(expectedResponse)));

            final EndpointMapper.Factory mapperFactory = parent -> {
                final int backendPort = backendUsesHttps ? backend.httpsPort() : backend.port();

                final BackendConfiguration backendConfig = backendUsesHttps
                        ? new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, true, backendCertificate, "changeit")
                        : new BackendConfiguration("test-backend", "localhost", backendPort, path, 1000, false);

                return new TestEndpointMapper(backendConfig, false);
            };

            try (final HttpProxyServer server = new HttpProxyServer(mapperFactory, tmpDir.getRoot())) {
                server.addCertificate(new SSLCertificateConfiguration("localhost", null, carapaceCertificate, "changeit", STATIC));

                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpPort, false, null, null,
                        DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000,
                        DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HttpProtocol.HTTP11),
                        new DefaultChannelGroup(new DefaultEventExecutor())));

                server.addListener(new NetworkListenerConfiguration(
                        "localhost", httpsPort, true, null, "localhost",
                        DEFAULT_SSL_PROTOCOLS, 128, true, 300, 60, 8, 1000,
                        DEFAULT_FORWARDED_STRATEGY, Set.of(), Set.of(HttpProtocol.HTTP11),
                        new DefaultChannelGroup(new DefaultEventExecutor())));

                server.start();

                final int port = clientUsesHttps ? httpsPort : httpPort;
                final String protocol = clientUsesHttps ? "https" : "http";
                final String url = protocol + "://localhost:" + port + path;

                final String response = IOUtils.toString(URI.create(url), StandardCharsets.UTF_8);
                assertEquals(expectedResponse, response);
            }
        } finally {
            backend.stop();
        }
    }
}
