package org.carapaceproxy.core;

import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import io.micrometer.core.instrument.Metrics;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.carapaceproxy.server.config.BackendConfiguration;
import org.carapaceproxy.server.config.ConnectionPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.resources.ConnectionProvider;

public class ConnectionsManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionsManager.class);
    private final Map<ConnectionPoolConfiguration, ConnectionProvider> connectionPools = new ConcurrentHashMap<>();
    private volatile ConnectionPoolConfiguration defaultConnectionPoolConfiguration;
    private volatile ConnectionProvider defaultConnectionPoolProvider;

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration, Collection<BackendConfiguration> newEndpoints) {
        close();

        // custom pools
        final var connectionPoolsCopy = new ArrayList<>(newConfiguration.getConnectionPools().values());

        // default pool
        connectionPoolsCopy.add(newConfiguration.getDefaultConnectionPool());

        connectionPoolsCopy.forEach(connectionPool -> {
            if (!connectionPool.isEnabled()) {
                return;
            }

            ConnectionProvider.Builder builder = ConnectionProvider.builder(connectionPool.getId())
                    .disposeTimeout(Duration.ofMillis(connectionPool.getDisposeTimeout()));

            // max connections per endpoint limit setup
            newEndpoints.forEach(be -> {
                LOGGER.debug(
                        "Setup max connections per endpoint {}:{} = {} for connectionpool {}", be.host(), be.port(), connectionPool.getMaxConnectionsPerEndpoint(), connectionPool.getId());
                builder.forRemoteHost(InetSocketAddress.createUnresolved(be.host(), be.port()), spec -> {
                    spec.maxConnections(connectionPool.getMaxConnectionsPerEndpoint());
                    spec.pendingAcquireTimeout(Duration.ofMillis(connectionPool.getBorrowTimeout()));
                    spec.maxIdleTime(Duration.ofMillis(connectionPool.getIdleTimeout()));
                    spec.maxLifeTime(Duration.ofMillis(connectionPool.getMaxLifeTime()));
                    spec.evictInBackground(Duration.ofMillis(connectionPool.getIdleTimeout() * 2L));
                    spec.metrics(true);
                    spec.lifo();
                });
            });

            if (connectionPool.getId().equals("*")) {
                defaultConnectionPoolConfiguration = connectionPool;
                defaultConnectionPoolProvider = builder.build();
            } else {
                connectionPools.put(connectionPool, builder.build());
            }
        });
    }

    @Override
    public void close() {
        connectionPools.values().forEach(ConnectionProvider::dispose); // graceful shutdown according to disposeTimeout
        connectionPools.clear();

        if (defaultConnectionPoolProvider != null) {
            // being it volatile, we don't have the compile-time certainty that it won't become null after the check;
            //  still, it is reasonably safe to assume that it will be not null
            Objects.requireNonNull(defaultConnectionPoolProvider).dispose();
        }

        Metrics.globalRegistry.forEachMeter(m -> {
            if (m.getId().getName().startsWith(CONNECTION_PROVIDER_PREFIX)) {
                Metrics.globalRegistry.remove(m);
            }
        });
    }

    public ConnectionPoolConfiguration findConnectionPool(final String hostName) {
        Objects.requireNonNull(hostName);
        return connectionPools.keySet().stream()
                .filter(e -> Pattern.matches(e.getDomain(), hostName))
                .findFirst()
                .orElse(defaultConnectionPoolConfiguration);
    }

    public ConnectionProvider getConnectionProvider(final String hostName) {
        return getConnectionProvider(findConnectionPool(hostName));
    }

    public ConnectionProvider getConnectionProvider(final ConnectionPoolConfiguration configuration) {
        if (configuration.equals(defaultConnectionPoolConfiguration)) {
            return defaultConnectionPoolProvider;
        }
        if (connectionPools.containsKey(configuration)) {
            return connectionPools.get(configuration);
        }
        throw new IllegalArgumentException("No connection provider for " + configuration);
    }
}
