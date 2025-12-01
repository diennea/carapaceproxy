package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import reactor.netty.http.HttpProtocol;

@RunWith(Parameterized.class)
public class XTlsCipherFilterTest extends AbstractXTlsFilterTest {

    public XTlsCipherFilterTest(boolean http1) throws Exception {
        super(http1);
    }

    @Parameterized.Parameters(name = "Use HTTP/1.x: {0}")
    public static Collection<Object[]> data() {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    private void setupWireMockForCipherFilter() {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .withHeader("X-Tls-Cipher", matching("TLS_.*"))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @Test
    public void testHttpsWithCipherAndProtocol() throws Exception {
        setupWireMockForCipherFilter();
        try (var server = startServer(true,
                new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()),
                new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of())
        )) {
            assertResponseContains(server.getLocalPort(), true, "it <b>works</b> !!");
        }
    }

    @Test
    public void testHttpsWithProtocolOnly() throws Exception {
        setupWireMockForCipherFilter();
        try (var server = startServer(true, new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
            assertResponseContains(server.getLocalPort(), true, "it <b>absent</b> !!");
        }
    }

    @Test
    public void testHttpWithCipherOnly() throws Exception {
        setupWireMockForCipherFilter();
        Set<HttpProtocol> protocols = http1 ? Set.of(HttpProtocol.HTTP11) : Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C);
        try (var server = startServer(false, new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()))) {
            assertResponseContains(server.getLocalPort(), false, "it <b>absent</b> !!");
        }
    }

    @Test
    public void testHttpWithoutFilter() throws Exception {
        setupWireMockForCipherFilter();
        try (var server = startServer(false)) {
            assertResponseContains(server.getLocalPort(), false, "it <b>absent</b> !!");
        }
    }
}
