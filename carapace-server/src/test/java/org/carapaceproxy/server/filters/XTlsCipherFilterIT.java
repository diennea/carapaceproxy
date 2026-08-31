package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import java.util.Set;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.netty.http.HttpProtocol;

public class XTlsCipherFilterIT extends AbstractXTlsFilterTest {

    private void setupWireMockForCipherFilter(WireMockServer wireMockRule) {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .withHeader("X-Tls-Cipher", matching("TLS_.*"))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Cipher", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    public void testHttpsWithCipherAndProtocol(boolean http1) throws Exception {
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

    @ParameterizedTest
    @CsvSource({"true", "false"})
    public void testHttpsWithProtocolOnly(boolean http1) throws Exception {
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

    @ParameterizedTest
    @CsvSource({"true", "false"})
    public void testHttpWithCipherOnly(boolean http1) throws Exception {
        Set<HttpProtocol> protocols = http1 ? Set.of(HttpProtocol.HTTP11) : Set.of(HttpProtocol.HTTP11, HttpProtocol.H2C);
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForCipherFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, false,
                    new RequestFilterConfiguration(XTlsCipherRequestFilter.TYPE, Map.of()))) {
                assertResponseContains(http1, server.getLocalPort(), false, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    public void testHttpWithoutFilter(boolean http1) throws Exception {
        WireMockServer wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForCipherFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, false)) {
                assertResponseContains(http1, server.getLocalPort(), false, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }
}
