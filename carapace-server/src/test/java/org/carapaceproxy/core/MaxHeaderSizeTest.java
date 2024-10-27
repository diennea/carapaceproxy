package org.carapaceproxy.core;

import org.carapaceproxy.api.UseAdminServer;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MaxHeaderSizeTest extends UseAdminServer {

    @RegisterExtension
    public static WireMockExtension wireMockRule = WireMockExtension.newInstance().options(WireMockConfiguration.options().port(0)).build();

    private Properties config;

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        startServer(config);

        // Default certificate
        String defaultCertificate = TestUtils.deployResource("ia.p12", tmpDir);
        config.put("certificate.1.hostname", "*");
        config.put("certificate.1.file", defaultCertificate);
        config.put("certificate.1.password", "changeit");

        // Listeners
        config.put("listener.1.host", "localhost");
        config.put("listener.1.port", "8086");
        config.put("listener.1.enabled", "true");
        config.put("listener.1.defaultcertificate", "*");

        // Backends
        config.put("backend.1.id", "localhost");
        config.put("backend.1.enabled", "true");
        config.put("backend.1.host", "localhost");
        config.put("backend.1.port", wireMockRule.getPort() + "");

        config.put("backend.2.id", "localhost2");
        config.put("backend.2.enabled", "true");
        config.put("backend.2.host", "localhost2");
        config.put("backend.2.port", wireMockRule.getPort() + "");

        // Default director
        config.put("director.1.id", "*");
        config.put("director.1.backends", "localhost");
        config.put("director.1.enabled", "true");

        // Default route
        config.put("route.100.id", "default");
        config.put("route.100.enabled", "true");
        config.put("route.100.match", "all");
        config.put("route.100.action", "proxy-all");

        changeDynamicConfiguration(config);


        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create("http://localhost:" + 8086 +"/index.html"))
                .setHeader("custom-header", "test")
                .setHeader("token", "eyJhbGciOiJIUzI1NiJ9.eyJSb")
                .setHeader("token1", "eyJhbGciOiJIUzI1NiJ9.eyJSb")
                .setHeader("token2", "eyJhbGciOiJIUzI1NiJ9.eyJSb")
                .setHeader("token3", "eyJhbGciOiJIUzI1NiJ9.eyJSb")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        config.put("carapace.maxheadersize", "1");
        changeDynamicConfiguration(config);

        HttpClient httpClient2 = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpResponse<String> response2 = httpClient2.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(431, response2.statusCode());
    }
}
