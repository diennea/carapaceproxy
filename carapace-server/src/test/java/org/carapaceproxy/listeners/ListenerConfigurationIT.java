package org.carapaceproxy.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.ListeningChannel;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
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

                //check default configuration
                assertThat(listeners.get(listenerKey).getConfig().keepAlive()).isTrue();
                assertThat(listeners.get(listenerKey).getConfig().soBacklog()).isEqualTo(128);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveIdle()).isEqualTo(300);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveInterval()).isEqualTo(60);
                assertThat(listeners.get(listenerKey).getConfig().keepAliveCount()).isEqualTo(8);
                assertThat(listeners.get(listenerKey).getConfig().maxKeepAliveRequests()).isEqualTo(1000);
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

    private void reloadConfiguration(Properties configuration, final HttpProxyServer server) throws
            ConfigurationChangeInProgressException, InterruptedException {
        PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
        server.applyDynamicConfigurationFromAPI(config);
    }

}
