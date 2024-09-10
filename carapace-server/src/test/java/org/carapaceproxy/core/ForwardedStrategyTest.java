package org.carapaceproxy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_COUNT;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_IDLE;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_KEEP_ALIVE_INTERVAL;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_MAX_KEEP_ALIVE_REQUESTS;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SO_BACKLOG;
import static org.carapaceproxy.server.config.NetworkListenerConfiguration.DEFAULT_SSL_PROTOCOLS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ForwardedStrategyTest {
    public static final String REAL_IP_ADDRESS = "127.0.0.1";
    public static final String FORWARDED_IP_ADDRESS = "1.2.3.4";
    public static final String HEADER_PRESENT = "Header present!";
    public static final String HEADER_REWRITTEN = "Header rewritten!";
    public static final String NO_HEADER = "No header!";
    public static final String SUBNET = "/24";

    @Parameterized.Parameters(name = "Use actual CIDR? {0}")
    public static Iterable<?> data() {
        return List.of(true, false);
    }

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Before
    public void setupWireMock() {
        stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Forwarded-For", equalTo(FORWARDED_IP_ADDRESS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(HEADER_PRESENT)));
        stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Forwarded-For", equalTo(REAL_IP_ADDRESS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(HEADER_REWRITTEN)));
        stubFor(get(urlEqualTo("/index.html"))
                .withHeader("X-Forwarded-For", absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(NO_HEADER)));
    }

    @Parameterized.Parameter
    public boolean useCidr;

    @Test
    public void testDropStrategy() throws IOException, ConfigurationNotValidException, InterruptedException {
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(getConfiguration(ForwardedStrategies.drop(), Set.of()));
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithoutHeader(client);
                assertThat(response, containsString(NO_HEADER));
            }
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithHeader(client);
                assertThat(response, containsString(NO_HEADER));
            }
        }
    }

    @Test
    public void testPreserveStrategy() throws IOException, ConfigurationNotValidException, InterruptedException {
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(getConfiguration(ForwardedStrategies.preserve(), Set.of()));
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithoutHeader(client);
                assertThat(response, containsString(NO_HEADER));
            }
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithHeader(client);
                assertThat(response, containsString(HEADER_PRESENT));
            }
        }
    }

    @Test
    public void testRewriteStrategy() throws IOException, ConfigurationNotValidException, InterruptedException {
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            server.addListener(getConfiguration(ForwardedStrategies.rewrite(), Set.of()));
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithoutHeader(client);
                assertThat(response, containsString(HEADER_REWRITTEN));
            }
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithHeader(client);
                assertThat(response, containsString(HEADER_REWRITTEN));
            }
        }
    }

    @Test
    public void testIfTrustedStrategy() throws IOException, ConfigurationNotValidException, InterruptedException {
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            final var trustedIps = Set.of(REAL_IP_ADDRESS + (useCidr ? SUBNET : ""));
            server.addListener(getConfiguration(ForwardedStrategies.ifTrusted(trustedIps), trustedIps));
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithoutHeader(client);
                assertThat(response, containsString(NO_HEADER));
            }
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithHeader(client);
                assertThat(response, containsString(HEADER_PRESENT));
            }
        }
    }

    @Test
    public void testIfNotTrustedStrategy() throws IOException, ConfigurationNotValidException, InterruptedException {
        final var mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (final var server = new HttpProxyServer(mapper, tmpDir.newFolder())) {
            final var trustedIps = Set.of(FORWARDED_IP_ADDRESS + (useCidr ? SUBNET : ""));
            server.addListener(getConfiguration(ForwardedStrategies.ifTrusted(trustedIps), trustedIps));
            server.start();
            int port = server.getLocalPort();
            assertTrue(port > 0);
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithoutHeader(client);
                assertThat(response, containsString(HEADER_REWRITTEN));
            }
            try (final var client = new RawHttpClient("localhost", port)) {
                final var response = requestWithHeader(client);
                assertThat(response, containsString(HEADER_REWRITTEN));
            }
        }
    }

    private static NetworkListenerConfiguration getConfiguration(final ForwardedStrategy strategy, final Set<String> trustedIps) {
        return new NetworkListenerConfiguration(
                "localhost",
                0,
                false,
                null,
                null,
                DEFAULT_SSL_PROTOCOLS,
                DEFAULT_SO_BACKLOG,
                true,
                DEFAULT_KEEP_ALIVE_IDLE,
                DEFAULT_KEEP_ALIVE_INTERVAL,
                DEFAULT_KEEP_ALIVE_COUNT,
                DEFAULT_MAX_KEEP_ALIVE_REQUESTS,
                strategy.name(),
                trustedIps
        );
    }

    private static String requestWithHeader(final RawHttpClient client) throws IOException {
        return client.executeRequest("""
                GET /index.html HTTP/1.1\r
                Host: localhost\r
                X-Forwarded-For: %s\r
                Connection: close\r
                \r
                """.formatted(FORWARDED_IP_ADDRESS)).toString();
    }

    private static String requestWithoutHeader(final RawHttpClient client) throws IOException {
        return client.executeRequest("""
                GET /index.html HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """).toString();
    }
}
