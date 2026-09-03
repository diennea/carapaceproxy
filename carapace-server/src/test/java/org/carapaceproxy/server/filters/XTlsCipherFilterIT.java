package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XTlsCipherFilterIT extends AbstractXTlsFilterTest {

    private void setupWireMockForCipherFilter(WireMockServer wireMockRule) {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .withHeader("X-Tls-Cipher", matching("TLS_.*"))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @ParameterizedTest(name = "http1={0}")
    @ValueSource(booleans = {true, false})
    void httpsWithCipherAndProtocol(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForCipherFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, true,
                    new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()),
                    new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
                assertResponseContains(http1, server.getLocalPort(), true, "it <b>works</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    @ParameterizedTest(name = "http1={0}")
    @ValueSource(booleans = {true, false})
    void httpsWithProtocolOnly(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForCipherFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, true,
                    new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
                assertResponseContains(http1, server.getLocalPort(), true, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    // Plaintext filter behaviour (!isSecure() -> no header) is covered by the unit
    // XTlsCipherRequestFilterTest. The no-filter plaintext case is covered once by
    // XTlsProtocolFilterIT.httpWithoutFilter (no filter configured, no filter code runs either way).
}
