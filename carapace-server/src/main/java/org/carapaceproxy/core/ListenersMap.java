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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.carapaceproxy.core.stats.PrometheusListenerStats;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
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
public class ListenersMap {

    public static final String OCSP_CERTIFICATE_CHAIN = "ocsp-certificate";

    private static final Logger LOG = LoggerFactory.getLogger(ListenersMap.class);

    private final HttpProxyServer parent;
    private final ConcurrentMap<String, SslContext> sslContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<HostPort, DisposableChannelListener> listeningChannels = new ConcurrentHashMap<>();
    private boolean started;
    private ConcurrentMap<String, SslContext> sslContextsCache;

    public ListenersMap(final HttpProxyServer parent) {
        this.parent = parent;
    }

    /* public int getLocalPort() {
        return this.isEmpty() ? -1 : this.values().iterator().next().getRealHostPort().port();
    } */

    public void start() throws InterruptedException, ConfigurationNotValidException {
        started = true;
        reloadConfiguration();
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

    /**
     * Re-apply the current configuration; it should be invoked after editing it.
     *
     * @throws InterruptedException if it is interrupted while starting or stopping a listener
     */
    public void reloadConfiguration() throws InterruptedException {
        sslContextsCache.clear();

        final var existingListeners = Set.copyOf(this.listeningChannels.values());
        this.listeningChannels.clear();
        for (final var existingListener : existingListeners) {
            final var newListener = existingListener.reloadOrNull();
            if (newListener != null) {
                this.listeningChannels.put(newListener.getHostPort(), newListener);
                if (started) {
                    newListener.start();
                }
            }
        }
        for (final var networkConfiguration : this.parent.getCurrentConfiguration().getListeners()) {
            final var hostPort = networkConfiguration.getKey();
            if (!this.listeningChannels.containsKey(hostPort)) {
                LOG.info("listener: {} is to be started", hostPort);
                final var listener = new DisposableChannelListener(hostPort, this.parent, PrometheusListenerStats.INSTANCE, sslContextsCache);
                this.listeningChannels.put(hostPort, listener);
                if (started) {
                    listener.start();
                }
            }
        }
    }

}
