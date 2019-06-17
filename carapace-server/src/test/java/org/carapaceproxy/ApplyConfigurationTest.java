package org.carapaceproxy;

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
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.impl.ConnectionsManagerImpl;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.mapper.StandardEndpointMapper;
import org.carapaceproxy.user.SimpleUserRealm;
import org.carapaceproxy.user.UserRealm;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.carapaceproxy.utils.TestUserRealm;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author enrico.olivelli
 */
public class ApplyConfigurationTest {

    @ClassRule
    public static WireMockRule wireMockRule = new WireMockRule(0);

    @BeforeClass
    public static void setupWireMock() {
        stubFor(get(urlEqualTo("/index.html?redir"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withHeader("Pragma", "no-cache")
                        .withHeader("Connection", "close")
                        .withBody("it <b>works</b> !!")));

    }

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    /**
     * Static mapper, so that it can be references by configuration
     */
    public static final class StaticEndpointMapper extends TestEndpointMapper {

        public StaticEndpointMapper() {
            super("localhost", wireMockRule.port());
        }

    }

    @Test
    public void testChangeListenersConfig() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }

            // start without listeners
            server.start();

            // start two listeners
            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "1423");
                configuration.put("listener.2.host", "localhost");
                configuration.put("listener.2.port", "1426");
                reloadConfiguration(configuration, server);
            }

            testIt(1423, true);
            testIt(1426, true);

            // restart listener 1
            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "1425");
                configuration.put("listener.2.host", "localhost");
                configuration.put("listener.2.port", "1426");
                reloadConfiguration(configuration, server);
            }

            testIt(1425, true);
            testIt(1426, true);

            // stop listener 2
            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "1425");
                reloadConfiguration(configuration, server);
            }

            testIt(1425, true);
            testIt(1426, false);

            // restart listerer 2
            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                configuration.put("listener.1.host", "localhost");
                configuration.put("listener.1.port", "1425");
                configuration.put("listener.2.host", "localhost");
                configuration.put("listener.2.port", "1426");
                reloadConfiguration(configuration, server);
            }

            testIt(1425, true);
            testIt(1426, true);

            // no more listeners
            {
                Properties configuration = new Properties();
                configuration.put("mapper.class", StaticEndpointMapper.class.getName());
                reloadConfiguration(configuration, server);
            }

            testIt(1425, false);
            testIt(1426, false);

        }
    }

    @Test
    public void testReloadMapper() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }
            server.start();

            {
                StandardEndpointMapper mapper = (StandardEndpointMapper) server.getMapper();
                assertEquals(0, mapper.getBackends().size());
            }

            // add backend
            {
                Properties configuration = new Properties();
                configuration.put("backend.1.id", "foo");
                configuration.put("backend.1.host", "my-host1");
                configuration.put("backend.1.port", "4213");
                configuration.put("backend.1.enabled", "true");
                reloadConfiguration(configuration, server);

                StandardEndpointMapper mapper = (StandardEndpointMapper) server.getMapper();
                assertEquals(1, mapper.getBackends().size());
                System.out.println("backends:" + mapper.getBackends());
                assertNotNull(mapper.getBackends().get("foo"));
            }

            // add second backend
            {
                Properties configuration = new Properties();
                configuration.put("backend.1.id", "foo");
                configuration.put("backend.1.host", "my-host1");
                configuration.put("backend.1.port", "4213");
                configuration.put("backend.1.enabled", "true");

                configuration.put("backend.2.id", "bar");
                configuration.put("backend.2.host", "my-host2");
                configuration.put("backend.2.port", "4213");
                configuration.put("backend.2.enabled", "true");
                reloadConfiguration(configuration, server);

                StandardEndpointMapper mapper = (StandardEndpointMapper) server.getMapper();

                assertEquals(2, mapper.getBackends().size());
                assertNotNull(mapper.getBackends().get("foo"));
                assertNotNull(mapper.getBackends().get("bar"));
            }

            // remove first backend
            {
                Properties configuration = new Properties();

                configuration.put("backend.2.id", "bar");
                configuration.put("backend.2.host", "my-host2");
                configuration.put("backend.2.port", "4213");
                configuration.put("backend.2.enabled", "true");
                reloadConfiguration(configuration, server);

                StandardEndpointMapper mapper = (StandardEndpointMapper) server.getMapper();
                assertEquals(1, mapper.getBackends().size());
                assertNull(mapper.getBackends().get("foo"));
                assertNotNull(mapper.getBackends().get("bar"));
            }

        }
    }

    @Test
    public void testUserRealm() throws Exception {

        // Default UserRealm
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            Properties configuration = new Properties();
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            server.start();

            UserRealm realm = server.getRealm();
            assertTrue(realm instanceof SimpleUserRealm);

            // default user with auth always valid
            SimpleUserRealm userRealm = (SimpleUserRealm) server.getRealm();
            assertEquals(1, userRealm.listUsers().size());

            assertNotNull(userRealm.login("test_0", "anypass0"));
            assertNotNull(userRealm.login("test_1", "anypass1"));
            assertNotNull(userRealm.login("test_2", "anypass2"));
        }

        // TestUserRealm
        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder())) {
            Properties configuration = new Properties();
            configuration.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm");
            configuration.put("user.test1", "pass1");
            configuration.put("user.test2", "pass2");
            server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            server.start();

            UserRealm realm = server.getRealm();
            assertTrue(realm instanceof TestUserRealm);
            TestUserRealm userRealm = (TestUserRealm) server.getRealm();
            assertEquals(2, userRealm.listUsers().size());
            assertNotNull(userRealm.login("test1", "pass1"));
            assertNotNull(userRealm.login("test2", "pass2"));
            assertNull(userRealm.login("test1", "pass3")); // wrong pass

            // Add new user
            configuration.put("user.test3", "pass3");
            reloadConfiguration(configuration, server);
            userRealm = (TestUserRealm) server.getRealm(); // realm re-created at each configuration reload
            assertEquals(3, userRealm.listUsers().size());
            assertNotNull(userRealm.login("test3", "pass3"));
        }
    }

    @Test
    public void testChangeConnectionManagerConfiguration() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                configuration.put("connectionsmanager.connecttimeout", "9473");
                configuration.put("connectionsmanager.idletimeout", "1000");
                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }

            {
                ConnectionsManagerImpl cnnManager = (ConnectionsManagerImpl) server.getConnectionsManager();
                assertNull(cnnManager.getStuckRequestsReaperFuture());
            }
            server.start();

            ScheduledFuture<?> stuckRequestsReaperFuture;
            {
                ConnectionsManagerImpl cnnManager = (ConnectionsManagerImpl) server.getConnectionsManager();
                assertEquals(9473, cnnManager.getConnectTimeout());
                assertEquals(1000, cnnManager.getIdleTimeout());
                stuckRequestsReaperFuture = cnnManager.getStuckRequestsReaperFuture();
                assertNotNull(stuckRequestsReaperFuture);
            }

            // change connecttimeout
            {
                Properties configuration = new Properties();
                configuration.put("connectionsmanager.connecttimeout", "9479");
                configuration.put("connectionsmanager.idletimeout", "1000");
                reloadConfiguration(configuration, server);

                ConnectionsManagerImpl cnnManager = (ConnectionsManagerImpl) server.getConnectionsManager();
                assertEquals(9479, cnnManager.getConnectTimeout());
                assertEquals(1000, cnnManager.getIdleTimeout());
                // this should not change
                assertSame(stuckRequestsReaperFuture, cnnManager.getStuckRequestsReaperFuture());
            }

            // change idletimeout, this will reschedule stuck request reaper
            {
                Properties configuration = new Properties();
                configuration.put("connectionsmanager.idletimeout", "2000");
                reloadConfiguration(configuration, server);

                ConnectionsManagerImpl cnnManager = (ConnectionsManagerImpl) server.getConnectionsManager();
                assertEquals(2000, cnnManager.getIdleTimeout());
                // this should change
                assertNotSame(stuckRequestsReaperFuture, cnnManager.getStuckRequestsReaperFuture());
                // first stuckRequestsReaperFuture should have been cancelled
                assertTrue(stuckRequestsReaperFuture.isCancelled());
            }

        }
    }

    @Test
    public void testChangeFiltersConfiguration() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                configuration.put("filter.1.type", "add-x-forwarded-for");
                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }
            server.start();
            assertEquals(1, server.getFilters().size());
            assertTrue(server.getFilters().get(0) instanceof XForwardedForRequestFilter);

            // add a filter
            {
                Properties configuration = new Properties();
                configuration.put("filter.1.type", "add-x-forwarded-for");
                configuration.put("filter.2.type", "match-user-regexp");
                reloadConfiguration(configuration, server);

                assertEquals(2, server.getFilters().size());
                assertTrue(server.getFilters().get(0) instanceof XForwardedForRequestFilter);
                assertTrue(server.getFilters().get(1) instanceof RegexpMapUserIdFilter);
            }

            // remove a filter
            {
                Properties configuration = new Properties();
                configuration.put("filter.2.type", "match-user-regexp");
                reloadConfiguration(configuration, server);

                assertEquals(1, server.getFilters().size());
                assertTrue(server.getFilters().get(0) instanceof RegexpMapUserIdFilter);
            }

        }
    }

    @Test
    public void testChangeBackendHealthManagerConfiguration() throws Exception {

        try (HttpProxyServer server = new HttpProxyServer(null, tmpDir.newFolder());) {

            {
                Properties configuration = new Properties();
                configuration.put("connectionsmanager.connecttimeout", "9479");
                server.configureAtBoot(new PropertiesConfigurationStore(configuration));
            }
            server.start();
            assertEquals(9479, server.getBackendHealthManager().getConnectTimeout());

            // change configuration
            {
                Properties configuration = new Properties();
                configuration.put("connectionsmanager.connecttimeout", "9233");
                reloadConfiguration(configuration, server);

                assertEquals(9233, server.getBackendHealthManager().getConnectTimeout());
            }

        }
    }

    private void reloadConfiguration(Properties configuration, final HttpProxyServer server) throws ConfigurationNotValidException, ConfigurationChangeInProgressException, InterruptedException {
        PropertiesConfigurationStore config = new PropertiesConfigurationStore(configuration);
        server.applyDynamicConfigurationFromAPI(config);
    }

    private void testIt(int port, boolean ok) throws URISyntaxException, IOException {
        try {
            String url = "http://localhost:" + port + "/index.html?redir";
            String s = IOUtils.toString(new URL(url).toURI(), "utf-8");
            System.out.println("RES FOR: " + url + " -> " + s);
            assertEquals("it <b>works</b> !!", s);
            if (!ok) {
                fail("Expecting an error for port " + port);
            }
        } catch (IOException err) {
            if (ok) {
                fail("unexpected errro " + err + " for port " + port);
            }
        }
    }

}
