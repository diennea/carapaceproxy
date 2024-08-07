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
package org.carapaceproxy.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationNotValidException;

/**
 * Main entrypoint for the {@code server} service.
 *
 * @author enrico.olivelli
 * @see org.carapaceproxy.launcher.ZooKeeperMainWrapper for the alternative entrypoint
 */
public class ServerMain implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());
    private static final CountDownLatch running = new CountDownLatch(1);

    private final ConfigurationStore configuration;
    private final PidFileLocker pidFileLocker;
    private HttpProxyServer server;
    private boolean started;
    private final File basePath;

    private static ServerMain runningInstance;

    public ServerMain(ConfigurationStore configuration, File basePath) {
        this.configuration = configuration;
        this.pidFileLocker = new PidFileLocker(basePath.toPath().toAbsolutePath());
        this.basePath = basePath;
    }

    @Override
    public void close() {

        if (server != null) {
            try {
                server.close();
            } catch (Exception ex) {
                Logger.getLogger(ServerMain.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                server = null;
            }
        }
        pidFileLocker.close();
        running.countDown();
    }

    public static void main(String... args) {
        try {
            Properties configuration = new Properties();
            File basePath = new File(System.getProperty("user.dir", "."));
            boolean configFileFromParameter = false;
            for (String arg : args) {
                if (!arg.isEmpty()) {
                    File configFile = new File(arg).getAbsoluteFile();
                    LOG.log(Level.SEVERE, "Reading configuration from {0}", configFile);
                    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                        configuration.load(reader);
                    }
                    basePath = configFile.getParentFile().getParentFile();
                    configFileFromParameter = true;
                }
            }
            if (!configFileFromParameter) {
                File configFile = new File("conf/server.properties").getAbsoluteFile();
                System.out.println("Reading configuration from " + configFile);
                if (configFile.isFile()) {
                    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                        configuration.load(reader);
                    }
                    basePath = configFile.getParentFile().getParentFile();
                }
            }

            LogManager.getLogManager().readConfiguration();

            Thread.setDefaultUncaughtExceptionHandler((Thread arg0, Throwable arg1) -> {
                LOG.log(Level.SEVERE, "Uncaught error, thread " + arg0, arg1);
            });

            Runtime.getRuntime().addShutdownHook(new Thread("ctrlc-hook") {

                @Override
                public void run() {
                    System.out.println("Ctrl-C trapped. Shutting down");
                    ServerMain _brokerMain = runningInstance;
                    if (_brokerMain != null) {
                        _brokerMain.close();
                    }
                }

            });
            ConfigurationStore configurationStore = new PropertiesConfigurationStore(configuration);
            runningInstance = new ServerMain(configurationStore, basePath);
            runningInstance.start();

            runningInstance.join();

        } catch (Exception t) {
            System.exit(1);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public static ServerMain getRunningInstance() {
        return runningInstance;
    }

    public HttpProxyServer getServer() {
        return server;
    }

    public void join() {
        try {
            running.await();
        } catch (InterruptedException discard) {
        }
        started = false;
    }

    /**
     * Start an {@link HttpProxyServer}.
     *
     * @throws ConfigurationNotValidException if the configuration provided at server boot is not valid
     * @throws InterruptedException           if it is interrupted while starting or stopping a listener
     * @throws KeyStoreException              if no {@code Provider} supports a {@code KeyStoreSpi} implementation for the specified type
     * @throws FileNotFoundException          if the SSL certificate file does not exist,
     *                                        or is a directory rather than a regular file,
     *                                        or for some other reason cannot be opened for reading.
     * @throws IOException                    if something goes wrong while locking the PID file,
     *                                        or if there is an I/O or format problem with the keystore data,
     *                                        or if a password is required but not given,
     *                                        or if the given password was incorrect.
     * @throws NoSuchAlgorithmException       if the algorithm used to check the integrity of the keystore cannot be found
     * @throws CertificateException           if any of the certificates in the keystore could not be loaded
     * @throws SecurityException              if a security manager exists and its {@code checkRead} method denies read access to the file.
     * @throws Exception                      If the component fails to start for some other reasons
     */
    public void start() throws Exception {
        pidFileLocker.lock();

        server = new HttpProxyServer(null, basePath);
        server.configureAtBoot(configuration);
        server.start();
        server.startMetrics();
        server.startAdminInterface();
        started = true;
    }

}
