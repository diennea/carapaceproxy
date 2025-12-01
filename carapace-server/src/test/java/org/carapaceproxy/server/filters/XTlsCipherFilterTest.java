package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.KeyStoreRule;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import reactor.netty.http.HttpProtocol;

@RunWith(Parameterized.class)
public class XTlsCipherFilterTest {

    public static final String TLS_PROTOCOL = "TLSv1.2";
    private final boolean http1;

    @ClassRule(order = 0)
    public static TemporaryFolder tmpDir = new TemporaryFolder();

    @ClassRule(order = 1)
    public static KeyStoreRule carapaceKeyStore = new KeyStoreRule(tmpDir);

    @ClassRule(order = 2)
    public static KeyStoreRule backendKeyStore = new KeyStoreRule(tmpDir);

    @Rule(order = 3)
    public WireMockRule wireMockRule;

    public XTlsCipherFilterTest(final boolean http1) throws GeneralSecurityException {
        this.http1 = http1;
        this.wireMockRule = new WireMockRule(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .http2TlsDisabled(http1)
                .http2PlainDisabled(http1)
                .keystoreType(backendKeyStore.getKeyStore().getType())
                .keystorePath(backendKeyStore.getKeyStoreFile().getAbsolutePath())
                .keystorePassword(new String(backendKeyStore.getKeyStorePassword()))
        );
        final SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        sslContext.init(null, carapaceKeyStore.getTrustManagerFactory().getTrustManagers(), null);
        SSLContext.setDefault(sslContext);
    }

    @Parameterized.Parameters(name = "Use HTTP/1.x: {0}")
    public static Collection<Object[]> data() {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    @Test
    public void testHttpsWithCipherAndProtocol() throws Exception {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .withHeader("X-Tls-Cipher", matching("TLS_.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("it <b>absent</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        final String certificate = carapaceKeyStore.getKeyStoreFile().getAbsolutePath();
        final String password = new String(carapaceKeyStore.getKeyStorePassword());

        NetworkListenerConfiguration listener = NetworkListenerConfiguration
                .withDefaultSsl("0.0.0.0", 0, "*", Set.of(http1 ? HttpProtocol.HTTP11 : HttpProtocol.H2));

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addCertificate(new SSLCertificateConfiguration("*", null, certificate, password, STATIC));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()));
            server.addListener(listener);
            server.start();
            int port = server.getLocalPort();

            if (http1) {
                try (RawHttpClient client = getHttp11Client(port)) {
                    String response = client.executeRequest("""
                            GET /index.html HTTP/1.1\r
                            Host: localhost\r
                            Connection: close\r
                            \r
                            """).toString();
                    System.out.println("response: " + response);
                    assertTrue(response.contains("it <b>works</b> !!"));
                }
            } else {
                try (HttpClient client = getHttp2Client()) {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("https://localhost:" + port + "/index.html"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    System.out.println("response: " + response.body());
                    assertTrue(response.body().contains("it <b>works</b> !!"));
                }
            }
        }
    }

    @Test
    public void testHttpsWithProtocolOnly() throws Exception {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("it <b>absent</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        final String certificate = carapaceKeyStore.getKeyStoreFile().getAbsolutePath();
        final String password = new String(carapaceKeyStore.getKeyStorePassword());

        NetworkListenerConfiguration listener = NetworkListenerConfiguration
                .withDefaultSsl("0.0.0.0", 0, "*", Set.of(http1 ? HttpProtocol.HTTP11 : HttpProtocol.H2));

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addCertificate(new SSLCertificateConfiguration("*", null, certificate, password, STATIC));
            server.addRequestFilter(new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()));
            server.addListener(listener);
            server.start();
            int port = server.getLocalPort();

            if (http1) {
                try (RawHttpClient client = getHttp11Client(port)) {
                    String response = client.executeRequest("""
                            GET /index.html HTTP/1.1\r
                            Host: localhost\r
                            Connection: close\r
                            \r
                            """).toString();
                    System.out.println("response: " + response);
                    assertTrue(response.contains("it <b>absent</b> !!"));
                }
            } else {
                try (HttpClient client = getHttp2Client()) {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("https://localhost:" + port + "/index.html"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    System.out.println("response: " + response.body());
                    assertTrue(response.body().contains("it <b>absent</b> !!"));
                }
            }
        }
    }

    @Test
    public void testHttpWithCipherOnly() throws Exception {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("it <b>absent</b> !!")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());

        final Set<HttpProtocol> protocols = http1
                ? Set.of(HttpProtocol.HTTP11)
                : Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C);

        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.addRequestFilter(new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()));
            server.addListener(NetworkListenerConfiguration.withoutSsl("localhost", 0, protocols));
            server.start();
            int port = server.getLocalPort();

            if (http1) {
                try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                    String response = client.executeRequest("""
                            GET /index.html HTTP/1.1\r
                            Host: localhost\r
                            Connection: close\r
                            \r
                            """).toString();
                    System.out.println("response: " + response);
                    assertTrue(response.contains("it <b>absent</b> !!"));
                }
            } else {
                try (HttpClient client = getHttp2Client()) {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:" + port + "/index.html"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    System.out.println("response: " + response.body());
                    assertTrue(response.body().contains("it <b>absent</b> !!"));
                }
            }
        }
    }

    private static HttpClient getHttp2Client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    }

    private static RawHttpClient getHttp11Client(final int port) throws IOException {
        return new RawHttpClient("localhost", port, true, null, new String[]{TLS_PROTOCOL}, null, 10_000);
    }
}
