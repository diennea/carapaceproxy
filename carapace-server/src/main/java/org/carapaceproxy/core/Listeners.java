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
package org.carapaceproxy.core;

import io.netty.handler.ssl.SslContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.carapaceproxy.core.ssl.CertificatesUtils;
import org.carapaceproxy.core.stats.PrometheusListenerStats;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collection of listeners waiting for incoming clients requests on the configured HTTP ports.
 * <br>
 * While the {@link RuntimeServerConfiguration} is actually <i>mutable</i>, this class won't watch it for updates;
 * the caller should request a {@link #reloadConfiguration() reload of the configuration} manually instead.
 *
 * @author enrico.olivelli
 */
public class Listeners {

    public static final String OCSP_CERTIFICATE_CHAIN = "ocsp-certificate";
    private static final Logger LOG = LoggerFactory.getLogger(Listeners.class);

    private final HttpProxyServer parent;
    private RuntimeServerConfiguration currentConfiguration;
    private final ConcurrentMap<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<HostPort, DisposableChannelListener> listeningChannels = new ConcurrentHashMap<>();
    private boolean started;

    public Listeners(HttpProxyServer parent) {
        this.parent = parent;
        this.currentConfiguration = parent.getCurrentConfiguration();
    }

    public int getLocalPort() {
        for (final var channel : listeningChannels.values()) {
            return channel.getActualPort();
        }
        return -1;
    }

    public ConcurrentMap<HostPort, DisposableChannelListener> getListeningChannels() {
        return listeningChannels;
    }

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadCurrentConfiguration();
    }

    /**
     * Re-apply the current configuration; it should be invoked after editing it.
     *
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     */
    public void reloadCurrentConfiguration() throws InterruptedException {
        reloadConfiguration(this.currentConfiguration);
    }

    /**
     * Apply a new configuration and refresh the listeners according to it.
     *
     * @param newConfiguration the configuration
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     * @see #reloadCurrentConfiguration()
     */
    void reloadConfiguration(final RuntimeServerConfiguration newConfiguration) throws InterruptedException {
        if (!started) {
            this.currentConfiguration = newConfiguration;
            return;
        }
        // Clear cached ssl contexts
        sslContexts.clear();

        // stop dropped listeners, start new one
        List<DisposableChannelListener> listenersToStop = new ArrayList<>();
        List<DisposableChannelListener> listenersToRestart = new ArrayList<>();
        for (Map.Entry<HostPort, DisposableChannelListener> channel : listeningChannels.entrySet()) {
            final var hostPort = channel.getKey();
            final var listener = channel.getValue();
            final var actualListenerConfig = currentConfiguration.getListener(hostPort);
            final var newConfigurationForListener = newConfiguration.getListener(hostPort);
            if (newConfigurationForListener == null) {
                LOG.info("listener: {} is to be shut down", hostPort);
                listenersToStop.add(listener);
            } else if (!newConfigurationForListener.equals(actualListenerConfig)
                       || newConfiguration.getResponseCompressionThreshold() != currentConfiguration.getResponseCompressionThreshold()
                       || newConfiguration.getMaxHeaderSize() != currentConfiguration.getMaxHeaderSize()) {
                LOG.info("listener: {} is to be restarted", hostPort);
                listenersToRestart.add(listener);
            }
            listener.clear();
        }
        List<HostPort> listenersToStart = new ArrayList<>();
        for (NetworkListenerConfiguration config : newConfiguration.getListeners()) {
            HostPort key = config.getKey();
            if (!listeningChannels.containsKey(key)) {
                LOG.info("listener: {} is to be started", key);
                listenersToStart.add(key);
            }
        }

        // apply new configuration, this has to be done before rebooting listeners
        currentConfiguration = newConfiguration;

        try {
            for (final var listener : listenersToStop) {
                final var hostPort = listener.getHostPort();
                LOG.info("Stopping {}", hostPort);
                listener.stop();
                listeningChannels.remove(hostPort);
            }

            for (final var listener : listenersToRestart) {
                final var hostPort = listener.getHostPort();
                LOG.info("Restart {}", hostPort);
                listener.stop();
                bootListener(hostPort, currentConfiguration.getListener(hostPort));
            }

            for (HostPort hostPort : listenersToStart) {
                LOG.info("Starting {}", hostPort);
                bootListener(hostPort, currentConfiguration.getListener(hostPort));
            }
        } catch (InterruptedException stopMe) {
            Thread.currentThread().interrupt();
            throw stopMe;
        }
    }

    private void bootListener(final HostPort hostPort, final NetworkListenerConfiguration configuration) {
        final var newListener = new DisposableChannelListener(
                hostPort, this.parent, configuration, PrometheusListenerStats.INSTANCE, sslContexts
        );
        listeningChannels.put(hostPort, newListener);
        newListener.start();
    }

    public void stop() {
        for (final var channel : listeningChannels.values()) {
            try {
                channel.stop();
            } catch (InterruptedException ex) {
                LOG.error("Interrupted while stopping a listener", ex);
                Thread.currentThread().interrupt();
            }
        }
        listeningChannels.clear();
    }

    public SSLCertificateConfiguration chooseCertificate(String sniHostname, String defaultCertificate) {
        return chooseCertificate(parent.getCurrentConfiguration(), sniHostname, defaultCertificate);
    }

    public static SSLCertificateConfiguration chooseCertificate(final RuntimeServerConfiguration currentConfiguration, String sniHostname, String defaultCertificate) {
        if (sniHostname == null) {
            sniHostname = "";
        }
        Map<String, SSLCertificateConfiguration> certificates = currentConfiguration.getCertificates();
        SSLCertificateConfiguration certificateMatchExact = null;
        SSLCertificateConfiguration certificateMatchNoExact = null;
        for (SSLCertificateConfiguration c : certificates.values()) {
            if (certificateMatches(sniHostname, c, true)) {
                certificateMatchExact = c;
            } else if (certificateMatches(sniHostname, c, false)) {
                if (certificateMatchNoExact == null || c.isMoreSpecific(certificateMatchNoExact)) {
                    certificateMatchNoExact = c;
                }
            }
        }
        SSLCertificateConfiguration choosen = null;
        if (certificateMatchExact != null) {
            choosen = certificateMatchExact;
        } else if (certificateMatchNoExact != null) {
            choosen = certificateMatchNoExact;
        }
        if (choosen == null) {
            choosen = certificates.get(defaultCertificate);
        }
        return choosen;
    }

    private static boolean certificateMatches(String hostname, SSLCertificateConfiguration c, boolean exact) {
        if (c.getSubjectAltNames() == null || c.getSubjectAltNames().isEmpty()) {
            if (exact) {
                return !c.isWildcard() && hostname.equals(c.getHostname());
            } else {
                return c.isWildcard() && hostname.endsWith(c.getHostname());
            }
        } else {
            for (var name : c.getNames()) {
                final var wildcard = CertificatesUtils.isWildcard(name);
                if (exact && !wildcard && hostname.equals(name)) {
                    return true;
                }
                if (!exact && wildcard && hostname.endsWith(CertificatesUtils.removeWildcard(name))) {
                    return true;
                }
            }
            return false;
        }
    }
}
