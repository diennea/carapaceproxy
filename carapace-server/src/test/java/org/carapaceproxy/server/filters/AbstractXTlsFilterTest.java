package org.carapaceproxy.server.filters;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.KeyStoreRule;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import reactor.netty.http.HttpProtocol;

public abstract class AbstractXTlsFilterTest {

    public static final String TLS_PROTOCOL = "TLSv1.2";

    @TempDir
    public static File tmpDir;

    @RegisterExtension
    public static KeyStoreRule carapaceKeyStore = new KeyStoreRule();

    @RegisterExtension
    public static KeyStoreRule backendKeyStore = new KeyStoreRule();

    @BeforeAll
    public static void setupDefaultSslContext() throws GeneralSecurityException {
        SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        sslContext.init(null, carapaceKeyStore.getTrustManagerFactory().getTrustManagers(), null);
        SSLContext.setDefault(sslContext);
    }

    // Per-test WireMock backend; parameterized tests can't feed a @Rule, so each test owns the lifecycle.
    protected WireMockServer newWireMock(boolean http1) {
        return new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .http2TlsDisabled(http1)
                .http2PlainDisabled(http1)
                .keystoreType(backendKeyStore.getKeyStore().getType())
                .keystorePath(backendKeyStore.getKeyStoreFile().getAbsolutePath())
                .keystorePassword(new String(backendKeyStore.getKeyStorePassword())));
    }

    protected HttpProxyServer startServer(WireMockServer wireMockRule, boolean http1, boolean ssl, RequestFilterConfiguration... filters)
            throws InterruptedException, ConfigurationNotValidException, IOException {
        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        final String certificate = carapaceKeyStore.getKeyStoreFile().getAbsolutePath();
        final String password = new String(carapaceKeyStore.getKeyStorePassword());

        final Set<HttpProtocol> protocols = ssl
                ? Set.of(http1 ? HttpProtocol.HTTP11 : HttpProtocol.H2)
                : http1
                    ? Set.of(HttpProtocol.HTTP11)
                    // for plaintext HTTP, we need both HTTP/1.1 and HTTP/2 to allow Upgrade: h2c
                    : Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C);
        NetworkListenerConfiguration listener = ssl
                ? NetworkListenerConfiguration.withDefaultSsl("0.0.0.0", 0, "*", protocols)
                : NetworkListenerConfiguration.withoutSsl("localhost", 0, protocols);

        HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir);
        if (ssl) {
            server.addCertificate(new SSLCertificateConfiguration(
                    "*", null, certificate, password, SSLCertificateConfiguration.CertificateMode.STATIC));
        }
        if (filters != null) {
            for (RequestFilterConfiguration filter : filters) {
                server.addRequestFilter(filter);
            }
        }
        server.addListener(listener);
        server.start();
        return server;
    }

    protected void assertResponseContains(boolean http1, int port, boolean ssl, String expected) throws IOException, InterruptedException {
        if (http1) {
            try (var client = new RawHttpClient("localhost", port, ssl, null, new String[]{TLS_PROTOCOL}, null, 10_000)) {
                String response = client.executeRequest("""
                        GET /index.html HTTP/1.1\r
                        Host: localhost\r
                        Connection: close\r
                        \r
                        """).toString();
                assertThat(response, containsString(expected));
            }
        } else {
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder().uri(URI.create((ssl ? "https" : "http") + "://localhost:" + port + "/index.html")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(response.body(), containsString(expected));
            }
        }
    }

}
