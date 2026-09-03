package org.carapaceproxy.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ListeningChannel;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ListenerConfigurationIT {

    @TempDir
    public File tmpDir;

    @Test
    void listenerKeepAliveConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, tmpDir)) {

            {
                Properties configuration = new Properties();
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "8080");
                configuration.put("listener.1.enabled", "true");

                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }
            server.start();

            EndpointKey listenerKey = new EndpointKey("localhost", 8080);

            {
                Map<EndpointKey, ListeningChannel> listeners = server.getListeners().getListeningChannels();

                // a listener declared with host and port alone must come up on every default: group holds a
                // live Netty channel group, and the three TLS components are filled in by the properties path
                final NetworkListenerConfiguration config = listeners.get(listenerKey).getConfig();
                assertThat(config)
                        .usingRecursiveComparison()
                        .ignoringFields("group", "defaultCertificate", "sslCiphers", "sslProtocols")
                        .isEqualTo(NetworkListenerConfiguration.withDefault("localhost", 8080));
                assertThat(config)
                        .extracting(NetworkListenerConfiguration::defaultCertificate,
                                NetworkListenerConfiguration::sslCiphers,
                                NetworkListenerConfiguration::sslProtocols)
                        .containsExactly("*", "", Set.of("TLSv1.2", "TLSv1.3"));
            }
            //disable keepAlive
            {
                Properties configuration = new Properties();
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "8080");
                configuration.put("listener.1.keepalive", "false");
                configuration.put("listener.1.enabled", "true");

                reloadConfiguration(configuration, server);

                Map<EndpointKey, ListeningChannel> listeners = server.getListeners().getListeningChannels();

                assertThat(listeners).hasSize(1);
                assertThat(listeners.get(listenerKey).getConfig().keepAlive()).isFalse();
            }

            //customize keepAlive options
            {
                Properties configuration = new Properties();
                // SSL Listener
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "8080");
                configuration.put("listener.1.keepalive", "true");
                configuration.put("listener.1.keepaliveidle", "10");
                configuration.put("listener.1.keepaliveinterval", "5");
                configuration.put("listener.1.keepalivecount", "2");
                configuration.put("listener.1.maxkeepaliverequests", "2");
                configuration.put("listener.1.sobacklog", "10");
                configuration.put("listener.1.enabled", "true");
                reloadConfiguration(configuration, server);

                Map<EndpointKey, ListeningChannel> listeners = server.getListeners().getListeningChannels();

                assertThat(listeners.get(listenerKey).getConfig().keepAlive()).isTrue();
                assertThat(listeners.get(listenerKey).getConfig().soBacklog()).isEqualTo(10);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveIdle()).isEqualTo(10);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveInterval()).isEqualTo(5);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveCount()).isEqualTo(2);
                assertThat(listeners.get(listenerKey).getConfig().maxKeepAliveRequests()).isEqualTo(2);
            }

            //negative maxkeepAliverequests
            // value accepted -1, 1, >0
            {
                try {
                    Properties configuration = new Properties();
                    configuration.put("listener.1.host", "localhost");
                    configuration.put("listener.1.port", "8080");
                    configuration.put("listener.1.keepalive", "true");
                    configuration.put("listener.1.maxkeepaliverequests", "-10"); // negative value not valid
                    configuration.put("listener.1.enabled", "true");

                    reloadConfiguration(configuration, server);

                } catch (IllegalArgumentException e) {
                    assertThat(e.getMessage()).contains("maxKeepAliveRequests must be positive or -1");
                }
            }

            // maxkeepAliverequests = 0
            // value accepted -1, 1, >0
            {
                try {
                    Properties configuration = new Properties();
                    configuration.put("listener.1.host", "localhost");
                    configuration.put("listener.1.port", "8080");
                    configuration.put("listener.1.keepalive", "true");
                    configuration.put("listener.1.maxkeepaliverequests", "0"); //0 is not valid
                    configuration.put("listener.1.enabled", "true");

                    reloadConfiguration(configuration, server);

                } catch (IllegalArgumentException e) {
                    assertThat(e.getMessage()).contains("maxKeepAliveRequests must be positive or -1");
                }
            }
        }
    }

    @Test
    void listenerWaitsForThePortToBeReleased() throws Exception {
        ServerSocket occupant = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = occupant.getLocalPort();
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(500); // the first binds fail, a retry within the second succeeds
                occupant.close();
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
        });
        release.start();
        try (HttpProxyServer server = newServerListeningOn(port)) {
            server.start();
            assertThat(server.getListeners().getListeningChannels()).containsKey(new EndpointKey("127.0.0.1", port));
        } finally {
            release.join();
        }
    }

    @Test
    void listenerFailsWhenThePortStaysBusy() throws Exception {
        try (ServerSocket occupant = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             HttpProxyServer server = newServerListeningOn(occupant.getLocalPort())) {
            long start = System.nanoTime();
            // the failed bind is logged, not thrown: the listener stays down
            server.start();
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(1_000_000_000L);
            assertThat(server.getListeners().getListeningChannels()).doesNotContainKey(new EndpointKey("127.0.0.1", occupant.getLocalPort()));
        }
    }

    private HttpProxyServer newServerListeningOn(int port) throws Exception {
        HttpProxyServer server = new HttpProxyServer(StandardEndpointMapper::new, tmpDir);
        Properties configuration = new Properties();
        configuration.put("listener.1.host", "127.0.0.1");
        configuration.put("listener.1.port", String.valueOf(port));
        configuration.put("listener.1.enabled", "true");
        server.configureAtBoot(new PropertiesConfigurationStore(configuration));
        return server;
    }

    private void reloadConfiguration(Properties configuration, final HttpProxyServer server) throws
            ConfigurationChangeInProgressException, InterruptedException {
        PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
        server.applyDynamicConfigurationFromAPI(config);
    }

}
