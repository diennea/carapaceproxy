package org.carapaceproxy.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.ws.rs.core.Response;
import org.carapaceproxy.utils.HttpTestUtils;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class ConnectionPoolsResourceIT extends UseAdminServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONNECTION_POOLS_PATH = "/api/connectionpools";
    private static final String CREATED = String.valueOf(Response.Status.CREATED.getStatusCode());
    private static final String OK = String.valueOf(Response.Status.OK.getStatusCode());
    private static final String DEFAULT_EXAMPLE_ORG = "example.org";
    private static final String ALTERNATIVE_EXAMPLE_COM = "example.com";
    private static final int MAX_CONNECTIONS_PER_ENDPOINT = 10;
    private static final int BORROW_TIMEOUT = 5000;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int STUCK_REQUEST_TIMEOUT = 15000;
    private static final int IDLE_TIMEOUT = 20000;
    private static final int MAX_LIFE_TIME = 100000;
    private static final int DISPOSE_TIMEOUT = 50000;
    private static final int KEEPALIVE_IDLE = 500;
    private static final int KEEPALIVE_INTERVAL = 50;
    private static final int KEEPALIVE_COUNT = 5;

    @RegisterExtension
    public WireMockExtension wireMockRule = WireMockExtension.newInstance().configureStaticDsl(true).options(WireMockConfiguration.options().port(0)).build();

    private void configureAndStartServer() throws Exception {

        HttpTestUtils.overrideJvmWideHttpsVerifier();

        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Content-Length", String.valueOf("it <b>works</b> !!".length()))
                        .withBody("it <b>works</b> !!")));

        final Properties config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        config.put("config.type", "database");
        config.put("db.jdbc.url", "jdbc:herddb:localhost");
        config.put("db.server.base.dir", tmpDir.getAbsolutePath());
        config.put("aws.accesskey", "accesskey");
        config.put("aws.secretkey", "secretkey");
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

        // Default connection pool properties
        config.put("connectionsmanager.maxconnectionsperendpoint", MAX_CONNECTIONS_PER_ENDPOINT);

        // Custom connection pool (with defaults)
        config.put("connectionpool.1.id", DEFAULT_EXAMPLE_ORG);
        config.put("connectionpool.1.domain", DEFAULT_EXAMPLE_ORG);
        config.put("connectionpool.1.maxconnectionsperendpoint", String.valueOf(MAX_CONNECTIONS_PER_ENDPOINT * 2));
        config.put("connectionpool.1.enabled", "true");

        changeDynamicConfiguration(config);
    }

    @Test
    void getSingle() throws Exception {
        configureAndStartServer();
        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var defaultResult = client.get(CONNECTION_POOLS_PATH + "/*", credentials);
            final var defaultResultBean = MAPPER.readValue(defaultResult.getBodyString(), ConnectionPoolsResource.ConnectionPoolBean.class);
            assertThat(defaultResultBean.getId()).isEqualTo("*");
            assertThat(defaultResultBean.getDomain()).isEqualTo("*");
            assertThat(defaultResultBean.getMaxConnectionsPerEndpoint()).isEqualTo(MAX_CONNECTIONS_PER_ENDPOINT);

            final var result = client.get(CONNECTION_POOLS_PATH + "/" + DEFAULT_EXAMPLE_ORG, credentials);
            final var resultBean = MAPPER.readValue(result.getBodyString(), ConnectionPoolsResource.ConnectionPoolBean.class);
            assertThat(resultBean.getId()).isEqualTo(DEFAULT_EXAMPLE_ORG);
            assertThat(resultBean.getDomain()).isEqualTo(DEFAULT_EXAMPLE_ORG);
            assertThat(resultBean.getMaxConnectionsPerEndpoint()).isEqualTo(MAX_CONNECTIONS_PER_ENDPOINT * 2);
        }
    }

    @Test
    void getAll() throws Exception {
        configureAndStartServer();

        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var result = client.get(CONNECTION_POOLS_PATH, credentials);
            final var resultMap = MAPPER.readValue(result.getBodyString(), new MapTypeReference());
            assertThat(resultMap.keySet()).isEqualTo(Set.of("*", DEFAULT_EXAMPLE_ORG));
        }
    }

    @Test
    void postNewConnectionPool() throws Exception {
        configureAndStartServer();
        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var pool = buildConnectionPoolBean();
            final var response = client.post(CONNECTION_POOLS_PATH, null, pool, credentials);
            assertThat(response.getStatusLine()).contains(CREATED);
            final var config = server.getCurrentConfiguration();
            assertThat(config.getConnectionPools().keySet()).isEqualTo(Set.of(DEFAULT_EXAMPLE_ORG, ALTERNATIVE_EXAMPLE_COM));
            final var newConnectionPool = config.getConnectionPools().get(ALTERNATIVE_EXAMPLE_COM);
            assertThat(newConnectionPool.getId()).isEqualTo(ALTERNATIVE_EXAMPLE_COM);
            assertThat(newConnectionPool.getDomain()).isEqualTo(ALTERNATIVE_EXAMPLE_COM);
            assertThat(newConnectionPool.getMaxConnectionsPerEndpoint()).isEqualTo(MAX_CONNECTIONS_PER_ENDPOINT * 3);
            assertThat(newConnectionPool.getBorrowTimeout()).isEqualTo(BORROW_TIMEOUT);
            assertThat(newConnectionPool.getConnectTimeout()).isEqualTo(CONNECT_TIMEOUT);
            assertThat(newConnectionPool.getStuckRequestTimeout()).isEqualTo(STUCK_REQUEST_TIMEOUT);
            assertThat(newConnectionPool.getIdleTimeout()).isEqualTo(IDLE_TIMEOUT);
            assertThat(newConnectionPool.getMaxLifeTime()).isEqualTo(MAX_LIFE_TIME);
            assertThat(newConnectionPool.getDisposeTimeout()).isEqualTo(DISPOSE_TIMEOUT);
            assertThat(newConnectionPool.getKeepaliveIdle()).isEqualTo(KEEPALIVE_IDLE);
            assertThat(newConnectionPool.getKeepaliveInterval()).isEqualTo(KEEPALIVE_INTERVAL);
            assertThat(newConnectionPool.getKeepaliveCount()).isEqualTo(KEEPALIVE_COUNT);
            assertThat(newConnectionPool.isKeepAlive()).isTrue();
            assertThat(newConnectionPool.isEnabled()).isTrue();
        }
    }

    @Test
    void putConnectionPoolModifications() throws Exception {
        configureAndStartServer();
        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var pool = buildConnectionPoolBean();
            pool.setId(DEFAULT_EXAMPLE_ORG);
            final var response = client.put(CONNECTION_POOLS_PATH + "/" + DEFAULT_EXAMPLE_ORG, null, pool, credentials);
            assertThat(response.getStatusLine()).contains(OK);
            final var config = server.getCurrentConfiguration();
            assertThat(config.getConnectionPools().keySet()).isEqualTo(Set.of(DEFAULT_EXAMPLE_ORG));
            final var newConnectionPool = config.getConnectionPools().get(DEFAULT_EXAMPLE_ORG);
            assertThat(newConnectionPool.getId()).isEqualTo(DEFAULT_EXAMPLE_ORG);
            assertThat(newConnectionPool.getDomain()).isEqualTo(ALTERNATIVE_EXAMPLE_COM);
            assertThat(newConnectionPool.getMaxConnectionsPerEndpoint()).isEqualTo(MAX_CONNECTIONS_PER_ENDPOINT * 3);
            assertThat(newConnectionPool.getBorrowTimeout()).isEqualTo(BORROW_TIMEOUT);
            assertThat(newConnectionPool.getConnectTimeout()).isEqualTo(CONNECT_TIMEOUT);
            assertThat(newConnectionPool.getStuckRequestTimeout()).isEqualTo(STUCK_REQUEST_TIMEOUT);
            assertThat(newConnectionPool.getIdleTimeout()).isEqualTo(IDLE_TIMEOUT);
            assertThat(newConnectionPool.getMaxLifeTime()).isEqualTo(MAX_LIFE_TIME);
            assertThat(newConnectionPool.getDisposeTimeout()).isEqualTo(DISPOSE_TIMEOUT);
            assertThat(newConnectionPool.getKeepaliveIdle()).isEqualTo(KEEPALIVE_IDLE);
            assertThat(newConnectionPool.getKeepaliveInterval()).isEqualTo(KEEPALIVE_INTERVAL);
            assertThat(newConnectionPool.getKeepaliveCount()).isEqualTo(KEEPALIVE_COUNT);
            assertThat(newConnectionPool.isKeepAlive()).isTrue();
            assertThat(newConnectionPool.isEnabled()).isTrue();
        }
    }

    @Test
    void regexDomainSurvivesSubsequentSaves() throws Exception {
        configureAndStartServer();
        final var regexDomain = "(\\Qhost.example\\E|\\Qother.example\\E)";
        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var pool = buildConnectionPoolBean();
            pool.setDomain(regexDomain);
            final var response = client.post(CONNECTION_POOLS_PATH, null, pool, credentials);
            assertThat(response.getStatusLine()).contains(CREATED);
            assertThat(server.getCurrentConfiguration().getConnectionPools().get(ALTERNATIVE_EXAMPLE_COM).getDomain()).isEqualTo(regexDomain);

            // any further save re-serializes the whole configuration: the regex pool must not lose its backslashes
            final var otherPool = buildConnectionPoolBean();
            otherPool.setId(DEFAULT_EXAMPLE_ORG);
            final var putResponse = client.put(CONNECTION_POOLS_PATH + "/" + DEFAULT_EXAMPLE_ORG, null, otherPool, credentials);
            assertThat(putResponse.getStatusLine()).contains(OK);

            final var result = client.get(CONNECTION_POOLS_PATH + "/" + ALTERNATIVE_EXAMPLE_COM, credentials);
            final var resultBean = MAPPER.readValue(result.getBodyString(), ConnectionPoolsResource.ConnectionPoolBean.class);
            assertThat(resultBean.getDomain()).isEqualTo(regexDomain);
        }
    }

    @Test
    void delete() throws Exception {
        configureAndStartServer();
        try (final var client = new RawHttpClient("localhost", 8761)) {
            final var response = client.delete(CONNECTION_POOLS_PATH + "/" + DEFAULT_EXAMPLE_ORG, credentials);
            assertThat(response.getStatusLine()).contains(OK);
            final var config = server.getCurrentConfiguration();
            assertThat(config.getConnectionPools()).isEmpty();
        }
    }

    private static ConnectionPoolsResource.ConnectionPoolBean buildConnectionPoolBean() {
        final var pool = new ConnectionPoolsResource.ConnectionPoolBean();
        pool.setId(ALTERNATIVE_EXAMPLE_COM);
        pool.setDomain(ALTERNATIVE_EXAMPLE_COM);
        pool.setMaxConnectionsPerEndpoint(MAX_CONNECTIONS_PER_ENDPOINT * 3);
        pool.setBorrowTimeout(BORROW_TIMEOUT);
        pool.setConnectTimeout(CONNECT_TIMEOUT);
        pool.setStuckRequestTimeout(STUCK_REQUEST_TIMEOUT);
        pool.setIdleTimeout(IDLE_TIMEOUT);
        pool.setMaxLifeTime(MAX_LIFE_TIME);
        pool.setDisposeTimeout(DISPOSE_TIMEOUT);
        pool.setKeepaliveIdle(KEEPALIVE_IDLE);
        pool.setKeepaliveInterval(KEEPALIVE_INTERVAL);
        pool.setKeepaliveCount(KEEPALIVE_COUNT);
        pool.setKeepAlive(true);
        pool.setEnabled(true);
        return pool;
    }

    private static class MapTypeReference extends TypeReference<Map<String, ConnectionPoolsResource.ConnectionPoolBean>> {
    }

}
