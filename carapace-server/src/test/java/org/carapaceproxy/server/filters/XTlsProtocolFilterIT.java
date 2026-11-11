package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class XTlsProtocolFilterIT extends AbstractXTlsFilterTest {

    private void setupWireMockForProtocolFilter(WireMockServer wireMockRule) {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHttpsWithFilter(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForProtocolFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, true,
                    new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
                assertResponseContains(http1, server.getLocalPort(), true, "it <b>works</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHttpsWithoutFilter(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForProtocolFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, true)) {
                assertResponseContains(http1, server.getLocalPort(), true, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    // Plaintext filter behaviour (!isSecure() -> no header) is covered by the unit
    // XTlsProtocolRequestFilterTest; only the HTTPS cases that need a real TLS handshake stay here.

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHttpWithoutFilter(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForProtocolFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, false)) {
                assertResponseContains(http1, server.getLocalPort(), false, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }
}
