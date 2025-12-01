package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class XTlsProtocolFilterTest extends AbstractXTlsFilterTest {

    public XTlsProtocolFilterTest(boolean http1) throws Exception {
        super(http1);
    }

    @Parameterized.Parameters(name = "Use HTTP/1.x: {0}")
    public static Collection<Object[]> data() {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    private void setupWireMockForProtocolFilter() {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @Test
    public void testHttpsWithFilter() throws Exception {
        setupWireMockForProtocolFilter();
        try (var server = startServer(true, new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of())
        )) {
            assertResponseContains(server.getLocalPort(), true, "it <b>works</b> !!");
        }
    }

    @Test
    public void testHttpsWithoutFilter() throws Exception {
        setupWireMockForProtocolFilter();
        try (var server = startServer(true)) {
            assertResponseContains(server.getLocalPort(), true, "it <b>absent</b> !!");
        }
    }

    @Test
    public void testHttpWithFilter() throws Exception {
        setupWireMockForProtocolFilter();
        try (var server = startServer(false, new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
            assertResponseContains(server.getLocalPort(), false, "it <b>absent</b> !!");
        }
    }

    @Test
    public void testHttpWithoutFilter() throws Exception {
        setupWireMockForProtocolFilter();
        try (var server = startServer(false)) {
            assertResponseContains(server.getLocalPort(), false, "it <b>absent</b> !!");
        }
    }
}
