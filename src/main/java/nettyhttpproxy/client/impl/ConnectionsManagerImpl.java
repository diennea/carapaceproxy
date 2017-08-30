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
package nettyhttpproxy.client.impl;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.client.ConnectionsManager;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointConnection;
import nettyhttpproxy.client.EndpointKey;
import nettyhttpproxy.client.EndpointNotAvailableException;
import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObjectInfo;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

/**
 * Implementation of the {@link ConnectionsManager} component
 *
 * @author enrico.olivelli
 */
public class ConnectionsManagerImpl implements ConnectionsManager, AutoCloseable {

    private final GenericKeyedObjectPool<EndpointKey, EndpointConnectionImpl> connections;
    private final int maxConnectionsPerEndpoint = 10;
    private final int borrowTimeout = 5000;
    private final ConcurrentHashMap<EndpointKey, EndpointStats> endpointsStats = new ConcurrentHashMap<>();
    private final EventLoopGroup group;

    void returnConnection(EndpointConnectionImpl con) {
        LOG.info("returnConnection:" + con);
        connections.returnObject(con.getKey(), con);
    }

    private final class ConnectionsFactory implements KeyedPooledObjectFactory<EndpointKey, EndpointConnectionImpl> {

        @Override
        public PooledObject<EndpointConnectionImpl> makeObject(EndpointKey k) throws Exception {
//            LOG.log(Level.INFO, "makeObject {0}", new Object[]{k});
            EndpointStats endpointstats = endpointsStats.computeIfAbsent(k, EndpointStats::new);
            EndpointConnectionImpl con = new EndpointConnectionImpl(k, ConnectionsManagerImpl.this, endpointstats);
            return new DefaultPooledObject<>(con);
        }

        @Override
        public void destroyObject(EndpointKey k, PooledObject<EndpointConnectionImpl> po) throws Exception {
//            LOG.log(Level.INFO, "destroyObject {0} {1}", new Object[]{k, po.getObject()});
            po.getObject().destroy();
        }

        @Override
        public boolean validateObject(EndpointKey k, PooledObject<EndpointConnectionImpl> po) {
            boolean valid = po.getObject().isValid();
            if (!valid) {
                LOG.log(Level.INFO, "validateObject {0} {1}-> {2}", new Object[]{k, po.getObject(), valid});
            }
            return valid;
        }

        @Override
        public void activateObject(EndpointKey k, PooledObject<EndpointConnectionImpl> po) throws Exception {
            LOG.log(Level.INFO, "activateObject {0} {1}", new Object[]{k, po.getObject()});
        }

        @Override
        public void passivateObject(EndpointKey k, PooledObject<EndpointConnectionImpl> po) throws Exception {
            LOG.log(Level.INFO, "passivateObject {0} {1}", new Object[]{k, po.getObject()});
        }

    }

    public ConnectionsManagerImpl() {
        GenericKeyedObjectPoolConfig config = new GenericKeyedObjectPoolConfig();
        config.setMaxTotalPerKey(maxConnectionsPerEndpoint);
        config.setMaxIdlePerKey(maxConnectionsPerEndpoint);
        config.setTestOnReturn(true);
        config.setBlockWhenExhausted(true);
        group = new NioEventLoopGroup();
        connections = new GenericKeyedObjectPool<>(new ConnectionsFactory(), config);
    }

    EventLoopGroup getGroup() {
        return group;
    }

    @Override
    public EndpointConnection getConnection(EndpointKey key) throws EndpointNotAvailableException {
        try {
            LOG.log(Level.INFO, "getConnection {0}", key);
            return connections.borrowObject(key, borrowTimeout);
        } catch (Exception ex) {
            throw new EndpointNotAvailableException(ex);
        }
    }
    private static final Logger LOG = Logger.getLogger(ConnectionsManagerImpl.class
        .getName());

    @Override
    public void close() {
        Map<String, List<DefaultPooledObjectInfo>> all = connections.listAllObjects();
        System.out.println("[POOL] numIdle: " + connections.getNumIdle() + " numActive: " + connections.getNumActive());
        connections.clear();
        System.out.println("[POOL] numIdle: " + connections.getNumIdle() + " numActive: " + connections.getNumActive());

        all.forEach((key, value) -> {
            System.out.println("[POOL] " + key + " -> " + value.size() + " connections");

            for (DefaultPooledObjectInfo info : value) {
                System.out.println("[POOL] " + key + " -> " + info.getPooledObjectToString());
            }
        });

        connections.close();
        group.shutdownGracefully();
    }

    final ConnectionsManagerStats stats = new ConnectionsManagerStats() {
        @Override
        public Map<EndpointKey, EndpointStats> getEndpoints() {
            return Collections.unmodifiableMap(endpointsStats);
        }
    };

    @Override
    public ConnectionsManagerStats getStats() {
        return stats;
    }

}
