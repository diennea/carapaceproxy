package org.carapaceproxy.server.filters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class XTlsProtocolFilterIT extends AbstractXTlsFilterTest {

    private void setupWireMockForProtocolFilter(WireMockRule wireMockRule) {
        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", equalTo(TLS_PROTOCOL))
                .willReturn(aResponse().withStatus(200).withBody("it <b>works</b> !!")));

        wireMockRule.stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Tls-Protocol", absent())
                .willReturn(aResponse().withStatus(200).withBody("it <b>absent</b> !!")));
    }

    @Test
    @Parameters({"true", "false"})
    public void testHttpsWithFilter(boolean http1) throws Exception {
        WireMockRule wireMockRule = newWireMock(http1);
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

    @Test
    @Parameters({"true", "false"})
    public void testHttpsWithoutFilter(boolean http1) throws Exception {
        WireMockRule wireMockRule = newWireMock(http1);
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

    @Test
    @Parameters({"true", "false"})
    public void testHttpWithFilter(boolean http1) throws Exception {
        WireMockRule wireMockRule = newWireMock(http1);
        wireMockRule.start();
        try {
            setupWireMockForProtocolFilter(wireMockRule);
            try (var server = startServer(wireMockRule, http1, false,
                    new RequestFilterConfiguration(XTlsProtocolRequestFilter.TYPE, Map.of()))) {
                assertResponseContains(http1, server.getLocalPort(), false, "it <b>absent</b> !!");
            }
        } finally {
            wireMockRule.stop();
        }
    }

    @Test
    @Parameters({"true", "false"})
    public void testHttpWithoutFilter(boolean http1) throws Exception {
        WireMockRule wireMockRule = newWireMock(http1);
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
