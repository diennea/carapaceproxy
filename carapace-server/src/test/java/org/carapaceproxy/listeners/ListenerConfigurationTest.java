package org.carapaceproxy.listeners;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import java.util.Properties;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.Listeners;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ListenerConfigurationTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testListenerKeepAliveConfiguration() throws Exception {
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

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
                Map<EndpointKey, Listeners.ListeningChannel> listeners = server.getListeners().getListeningChannels();

                //check default configuration
                assertTrue(listeners.get(listenerKey).getConfig().isKeepAlive());
                assertEquals(128, listeners.get(listenerKey).getConfig().getSoBacklog());
                assertEquals(300, listeners.get(listenerKey).getConfig().getKeepAliveIdle());
                assertEquals(60, listeners.get(listenerKey).getConfig().getKeepAliveInterval());
                assertEquals(8, listeners.get(listenerKey).getConfig().getKeepAliveCount());
                assertEquals(1000, listeners.get(listenerKey).getConfig().getMaxKeepAliveRequests());
            }
            //disable keepAlive
            {
                Properties configuration = new Properties();
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "8080");
                configuration.put("listener.1.keepalive", "false");
                configuration.put("listener.1.enabled", "true");

                reloadConfiguration(configuration, server);

                Map<EndpointKey, Listeners.ListeningChannel> listeners = server.getListeners().getListeningChannels();

                assertEquals(1, listeners.size());
                assertFalse(listeners.get(listenerKey).getConfig().isKeepAlive());
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

                Map<EndpointKey, Listeners.ListeningChannel> listeners = server.getListeners().getListeningChannels();

                assertTrue(listeners.get(listenerKey).getConfig().isKeepAlive());
                assertEquals(10, listeners.get(listenerKey).getConfig().getSoBacklog());
                assertEquals(10, listeners.get(listenerKey).getConfig().getKeepAliveIdle());
                assertEquals(5, listeners.get(listenerKey).getConfig().getKeepAliveInterval());
                assertEquals(2, listeners.get(listenerKey).getConfig().getKeepAliveCount());
                assertEquals(2, listeners.get(listenerKey).getConfig().getMaxKeepAliveRequests());
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
                    assertTrue(e.getMessage().contains("maxKeepAliveRequests must be positive or -1"));
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
                    assertTrue(e.getMessage().contains("maxKeepAliveRequests must be positive or -1"));
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
