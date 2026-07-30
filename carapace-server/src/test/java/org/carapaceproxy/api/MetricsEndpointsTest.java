/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.carapaceproxy.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.carapaceproxy.utils.TestUtils;
import org.junit.Rule;
import org.junit.Test;

/**
 * Guards the two metric-scraping endpoints of the admin interface: {@code /metrics}, backed by the
 * Prometheus simpleclient default registry, and {@code /micrometrics}, backed by the Micrometer
 * registry. Both must keep exposing their metrics across dependency upgrades.
 * <p>
 * The expectations below are the complete name sets, asserted exactly, so that a dependency upgrade
 * adding or dropping a metric shows up here and has to be acknowledged rather than slipping through.
 */
public class MetricsEndpointsTest extends UseAdminServer {

    /**
     * Every metric {@code /metrics} exposes: the ones this proxy registers itself, through
     * {@link org.carapaceproxy.utils.PrometheusUtils}, plus the {@code _created} series the
     * Prometheus text format emits alongside each counter.
     */
    private static final Set<String> EXPECTED_METRICS = Set.of(
            "backends_pending_requests",
            "backends_stuck_requests_created",
            "backends_stuck_requests_total",
            "cacheAllocator_cache_pooled_allocator_direct_memory_usage",
            "cacheAllocator_cache_unpooled_allocator_direct_memory_usage",
            "cache_hits_created",
            "cache_hits_total",
            "cache_misses_created",
            "cache_misses_total",
            "cache_non_cacheable_requests_created",
            "cache_non_cacheable_requests_total",
            "cache_payload_memory_usage_bytes",
            "cache_total_memory_usage_bytes",
            "clients_current_connected",
            "health_backend_status",
            "listeners_requests_created",
            "listeners_requests_total");

    /**
     * Every metric {@code /micrometrics} exposes: those Reactor Netty publishes into the Micrometer
     * registry, renamed for Prometheus.
     */
    private static final Set<String> EXPECTED_MICROMETRICS = Set.of(
            "reactor_netty_bytebuf_allocator_active_direct_memory",
            "reactor_netty_bytebuf_allocator_active_heap_memory",
            "reactor_netty_bytebuf_allocator_chunk_size",
            "reactor_netty_bytebuf_allocator_direct_arenas",
            "reactor_netty_bytebuf_allocator_heap_arenas",
            "reactor_netty_bytebuf_allocator_normal_cache_size",
            "reactor_netty_bytebuf_allocator_small_cache_size",
            "reactor_netty_bytebuf_allocator_threadlocal_caches",
            "reactor_netty_bytebuf_allocator_used_direct_memory",
            "reactor_netty_bytebuf_allocator_used_heap_memory",
            "reactor_netty_connection_provider_active_connections",
            "reactor_netty_connection_provider_active_streams",
            "reactor_netty_connection_provider_idle_connections",
            "reactor_netty_connection_provider_max_connections",
            "reactor_netty_connection_provider_max_pending_connections",
            "reactor_netty_connection_provider_pending_connections",
            "reactor_netty_connection_provider_pending_connections_time_seconds",
            "reactor_netty_connection_provider_pending_connections_time_seconds_max",
            "reactor_netty_connection_provider_pending_streams",
            "reactor_netty_connection_provider_pending_streams_time_seconds",
            "reactor_netty_connection_provider_pending_streams_time_seconds_max",
            "reactor_netty_connection_provider_total_connections",
            "reactor_netty_eventloop_pending_tasks",
            "reactor_netty_http_server_connections_active",
            "reactor_netty_http_server_connections_total");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(0);

    @Test
    public void bothEndpointsExposeMetrics() throws Exception {
        stubFor(get(urlEqualTo("/index.html"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("it <b>works</b> !!")));

        final int listenerPort = TestUtils.getFreePort();
        final Properties config = new Properties(HTTP_ADMIN_SERVER_CONFIG);
        startServer(config);

        config.put("listener.1.host", "localhost");
        config.put("listener.1.port", String.valueOf(listenerPort));
        config.put("listener.1.enabled", "true");

        config.put("backend.1.id", "localhost");
        config.put("backend.1.enabled", "true");
        config.put("backend.1.host", "localhost");
        config.put("backend.1.port", String.valueOf(wireMockRule.port()));

        config.put("director.1.id", "*");
        config.put("director.1.backends", "localhost");
        config.put("director.1.enabled", "true");

        config.put("route.100.id", "default");
        config.put("route.100.enabled", "true");
        config.put("route.100.match", "all");
        config.put("route.100.action", "proxy-all");

        changeDynamicConfiguration(config);

        // traverse the proxy once, so that the listener and Reactor Netty publish their meters
        final HttpResponse<String> response = httpGet(URI.create("http://localhost:" + listenerPort + "/index.html"));
        assertEquals(200, response.statusCode());
        assertEquals("it <b>works</b> !!", response.body());

        assertEquals("metrics exposed by /metrics changed", EXPECTED_METRICS, metricNames("/metrics"));
        assertEquals("metrics exposed by /micrometrics changed", EXPECTED_MICROMETRICS, metricNames("/micrometrics"));
    }

    /**
     * Scrapes an admin endpoint and collects the metric names it declares.
     *
     * @param path the admin path to scrape, for instance {@code /metrics}
     * @return the declared metric names, sorted
     * @throws IOException if the endpoint cannot be read
     * @throws InterruptedException if the scrape is interrupted
     */
    private static Set<String> metricNames(final String path) throws IOException, InterruptedException {
        final HttpResponse<String> response = httpGet(URI.create("http://localhost:" + DEFAULT_ADMIN_PORT + path));
        assertEquals("could not scrape " + path, 200, response.statusCode());
        return response.body().lines()
                .filter(line -> line.startsWith("# TYPE "))
                .map(line -> line.split(" ")[2])
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Performs a GET with finite connect and request timeouts, so that a wedged server fails the
     * test instead of hanging it.
     *
     * @param uri the URI to fetch
     * @return the response, with its body read as a string
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private static HttpResponse<String> httpGet(final URI uri) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(
                HttpRequest.newBuilder().GET().uri(uri).timeout(TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
