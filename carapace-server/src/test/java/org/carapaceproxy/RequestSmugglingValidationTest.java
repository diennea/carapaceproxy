package org.carapaceproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for built-in request smuggling validation in the core request processing pipeline.
 */
public class RequestSmugglingValidationTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    /**
     * Extract status code from HTTP status line (e.g., "HTTP/1.1 400 Bad Request" -> 400)
     */
    private int getStatusCode(RawHttpClient.HttpResponse response) {
        String statusLine = response.getStatusLine();
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            return Integer.parseInt(parts[1]);
        }
        throw new IllegalArgumentException("Invalid status line: " + statusLine);
    }

    @Test
    public void testContentLengthZeroWithBodyBlocked() throws Exception {
        stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String request = """
                        POST /test HTTP/1.1\r
                        Host: localhost\r
                        Content-Length: 0\r
                        Connection: close\r
                        \r
                        GET /malicious HTTP/1.1\r
                        Host: evil.com\r
                        \r
                        """;

                RawHttpClient.HttpResponse response = client.executeRequest(request);
                int statusCode = getStatusCode(response);
                assertEquals("Expected 400 Bad Request for Content-Length: 0 with body", 400, statusCode);
            }
        }
    }

    @Test
    public void testBothContentLengthAndTransferEncodingHandledByReactorNetty() throws Exception {
        stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK")));

        stubFor(get(urlEqualTo("/admin"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("ADMIN_ACCESS")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                // Craft a CL + TE smuggling payload as described in the issue
                // This includes both Content-Length and Transfer-Encoding headers
                // with a smuggled GET /admin request after the chunk terminator
                String smuggleRequest = """
                        POST /test HTTP/1.1\r
                        Host: localhost\r
                        Content-Length: 100\r
                        Transfer-Encoding: chunked\r
                        Connection: close\r
                        \r
                        4\r
                        test\r
                        0\r
                        \r
                        GET /admin HTTP/1.1\r
                        Host: evil.com\r
                        \r
                        """;

                RawHttpClient.HttpResponse response = client.executeRequest(smuggleRequest);
                int statusCode = getStatusCode(response);
                assertEquals("Expected 200 OK since Reactor Netty normalizes conflicting headers", 200, statusCode);
                assertEquals("OK", response.getBodyString());

                List<LoggedRequest> postRequests = findAll(postRequestedFor(urlEqualTo("/test")));
                assertEquals("Proxy must forward only the original POST", 1, postRequests.size());

                LoggedRequest postRequest = postRequests.get(0);
                assertEquals("POST", postRequest.getMethod().getName());
                assertEquals("/test", postRequest.getUrl());
                assertEquals("test", postRequest.getBodyAsString());

                List<LoggedRequest> adminRequests = findAll(getRequestedFor(urlEqualTo("/admin")));
                assertEquals("No smuggled GET /admin request should reach the backend", 0, adminRequests.size());
            }
        }
    }


    @Test
    public void testOnlyContentLengthAllowed() throws Exception {
        stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String request = """
                        POST /test HTTP/1.1\r
                        Host: localhost\r
                        Content-Length: 4\r
                        Connection: close\r
                        \r
                        test""";

                RawHttpClient.HttpResponse response = client.executeRequest(request);
                assertEquals(200, getStatusCode(response));
                assertEquals("OK", response.getBodyString());
            }
        }
    }

    @Test
    public void testOnlyTransferEncodingAllowed() throws Exception {
        stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String request = """
                        POST /test HTTP/1.1\r
                        Host: localhost\r
                        Transfer-Encoding: chunked\r
                        Connection: close\r
                        \r
                        4\r
                        test\r
                        0\r
                        \r
                        """;

                RawHttpClient.HttpResponse response = client.executeRequest(request);
                assertEquals(200, getStatusCode(response));
                assertEquals("OK", response.getBodyString());
            }
        }
    }

    @Test
    public void testNormalGetRequestAllowed() throws Exception {
        stubFor(get(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("OK")));

        TestEndpointMapper mapper = new TestEndpointMapper("localhost", wireMockRule.port());
        try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, tmpDir.newFolder())) {
            server.start();
            int port = server.getLocalPort();

            try (RawHttpClient client = new RawHttpClient("localhost", port)) {
                String request = """
                        GET /test HTTP/1.1\r
                        Host: localhost\r
                        Connection: close\r
                        \r
                        """;

                RawHttpClient.HttpResponse response = client.executeRequest(request);
                assertEquals(200, getStatusCode(response));
                assertEquals("OK", response.getBodyString());
            }
        }
    }
}
