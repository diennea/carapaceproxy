package org.carapaceproxy;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Properties;
import org.carapaceproxy.api.UseAdminServer;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class MaintenanceModeIT extends UseAdminServer {

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    private Properties config;

    @Test
    void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("healthmanager.tolerant", "true");
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
        config.put("backend.1.port", String.valueOf(wireMockRule.getPort()));

        config.put("backend.2.id", "localhost2");
        config.put("backend.2.enabled", "true");
        config.put("backend.2.host", "localhost2");
        config.put("backend.2.port", String.valueOf(wireMockRule.getPort()));

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
                .uri(URI.create("http://localhost:" + 8086 + "/index.html"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("it <b>works</b> !!");

        //enable maintenance mode
        config.put("carapace.maintenancemode.enabled", "true");
        changeDynamicConfiguration(config);
        HttpResponse<String> response2 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response2.statusCode()).isEqualTo(500);
        assertThat(response2.body()).contains("Maintenance in progress");

        //disable maintenance mode
        config.put("carapace.maintenancemode.enabled", "false");
        changeDynamicConfiguration(config);
        HttpResponse<String> response3 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response3.body()).isEqualTo("it <b>works</b> !!");

    }


    @Test
    void maintenanceModeApiTest() throws Exception {

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", "it <b>works</b> !!".length() + "")
                        .withBody("it <b>works</b> !!")));

        config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("healthmanager.tolerant", "true");
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
        config.put("backend.1.port", String.valueOf(wireMockRule.getPort()));

        config.put("backend.2.id", "localhost2");
        config.put("backend.2.enabled", "true");
        config.put("backend.2.host", "localhost2");
        config.put("backend.2.port", String.valueOf(wireMockRule.getPort()));

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
                .uri(URI.create("http://localhost:" + 8086 + "/index.html"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("it <b>works</b> !!");

        // ENABLE MAINTENANCE MODE VIA API
        HttpRequest enableMaintenanceRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.noBody())
                .uri(URI.create("http://localhost:" + DEFAULT_ADMIN_PORT + "/api/config/maintenance?enable=true"))
                .header("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString((DEFAULT_USERNAME + ":" + DEFAULT_PASSWORD).getBytes()))
                .build();

        HttpResponse<String> enableMaintenanceModeResponse = httpClient.send(enableMaintenanceRequest, HttpResponse.BodyHandlers.ofString());
        assertThatJson(enableMaintenanceModeResponse.body()).isEqualTo("{ok: true, error: ''}");

        HttpResponse<String> response2 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response2.statusCode()).isEqualTo(500);
        assertThat(response2.body()).contains("Maintenance in progress");

        //DISABLE MAINTENANCE MODE VIA API
        HttpRequest disableMaintenanceModeRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.noBody())
                .uri(URI.create("http://localhost:" + DEFAULT_ADMIN_PORT + "/api/config/maintenance?enable=false"))
                .header("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString((DEFAULT_USERNAME + ":" + DEFAULT_PASSWORD).getBytes()))
                .build();

        HttpResponse<String> disableMaintenanceModeResponse = httpClient.send(disableMaintenanceModeRequest, HttpResponse.BodyHandlers.ofString());
        // ok is the maintenance-mode state the call left behind, not whether the call succeeded
        assertThatJson(disableMaintenanceModeResponse.body()).isEqualTo("{ok: false, error: ''}");

        HttpResponse<String> response3 = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response3.body()).isEqualTo("it <b>works</b> !!");

    }

}
