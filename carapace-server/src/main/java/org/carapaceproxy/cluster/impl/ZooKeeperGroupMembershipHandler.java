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
package org.carapaceproxy.cluster.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.curator.ensemble.fixed.FixedEnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.imps.DefaultACLProvider;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZookeeperFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation based on ZooKeeper. This class is very simple, we are not expecting heavy traffic on ZooKeeper. We have two systems:
 * <ul>
 * <li>Peer discovery
 * <li>Configuration changes event broadcast
 * </ul>
 *
 * @author eolivelli
 */
public class ZooKeeperGroupMembershipHandler implements GroupMembershipHandler, AutoCloseable {

    public static final String PROPERTY_PEER_ADMIN_SERVER_HOST = "peer_admin_server_host"; // host of the Admin UI/API
    public static final String PROPERTY_PEER_ADMIN_SERVER_PORT = "peer_admin_server_port"; // port of the Admin UI/API
    public static final String PROPERTY_PEER_ADMIN_SERVER_HTTPS_PORT = "peer_admin_server_https_port"; // https port of the Admin UI/API

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperGroupMembershipHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CuratorFramework client;
    private final String peerId; // of the local one
    private final Map<String, String> peerInfo; // of the local one
    private final CopyOnWriteArrayList<PathChildrenCache> watchedEvents = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, InterProcessMutex> mutexes = new ConcurrentHashMap<>();
    private final ExecutorService callbacksExecutor = Executors.newSingleThreadExecutor();

    public ZooKeeperGroupMembershipHandler(String zkAddress, int zkTimeout, boolean zkAcl,
                                           String peerId, Map<String, String> peerInfo,
                                           Properties zkProperties) {
        ACLProvider aclProvider = new DefaultACLProvider();
        if (zkAcl) {
            aclProvider = new ACLProvider() {
                @Override
                public List<ACL> getDefaultAcl() {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }

                @Override
                public List<ACL> getAclForPath(String path) {
                    return getDefaultAcl();
                }
            };
        }

        final ZKClientConfig zkClientConfig = new ZKClientConfig();
        zkProperties.forEach((k, v) -> {
            zkClientConfig.setProperty(k.toString(), v.toString());
            LOG.info("Setting ZK client config: {}={}", k, v);
        });
        ZookeeperFactory zkFactory = (String connect, int timeout, Watcher wtchr, boolean canBeReadOnly) -> {
            LOG.info("Creating ZK client: {}, timeout {}, canBeReadOnly:{}", connect, timeout, canBeReadOnly);
            return new ZooKeeper(connect, timeout, wtchr, canBeReadOnly, zkClientConfig);
        };

        client = CuratorFrameworkFactory
                .builder()
                .aclProvider(aclProvider)
                .zookeeperFactory(zkFactory)
                .sessionTimeoutMs(zkTimeout)
                .waitForShutdownTimeoutMs(1000) // useful for tests
                .retryPolicy(new ExponentialBackoffRetry(1000, 2))
                .ensembleProvider(new FixedEnsembleProvider(zkAddress, true))
                .build();
        LOG.info("Waiting for ZK client connection to be established");
        try {
            boolean ok = client.blockUntilConnected(zkTimeout, TimeUnit.MILLISECONDS);
            if (!ok) {
                LOG.error("First connection to ZK cannot be established");
            } else {
                LOG.error("First connection to ZK established with success");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
        this.peerId = peerId;
        this.peerInfo = peerInfo;
    }

    @Override
    public void start() {
        try {
            client.start();
            client.blockUntilConnected();
            {
                Stat exists = client.checkExists().creatingParentsIfNeeded()
                        .forPath("/proxy/peers/" + peerId);
                if (exists == null) {
                    client.create()
                            .creatingParentsIfNeeded()
                            .withMode(CreateMode.EPHEMERAL) // auto delete on close
                            .forPath("/proxy/peers/" + peerId);
                }
                // Setting up local peer info
                storeLocalPeerInfo(peerInfo);
            }
            {
                final String path = "/proxy/events";
                Stat exists = client.checkExists().creatingParentsIfNeeded()
                        .forPath(path);
                if (exists == null) {
                    client.create()
                            .creatingParentsIfNeeded()
                            .withMode(CreateMode.PERSISTENT)
                            .forPath(path);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void storeLocalPeerInfo(Map<String, String> info) {
        try {
            String path = "/proxy/peers/" + peerId;
            byte[] data = MAPPER.writeValueAsBytes(info);
            client.setData().forPath(path, data);
        } catch (Exception ex) {
            LOG.error("Cannot store info for peer {}", peerId, ex);
        }
    }

    @Override
    public Map<String, String> loadInfoForPeer(String id) {
        try {
            String path = "/proxy/peers/" + id;
            Stat exists = client.checkExists().creatingParentsIfNeeded().forPath(path);
            if (exists != null) {
                byte[] data = client.getData().forPath(path); // Should be at least an empty Map.
                if (data != null) {
                    return MAPPER.readValue(new ByteArrayInputStream(data), Map.class);
                }
            }
        } catch (Exception ex) {
            LOG.error("Cannot load info for peer {}", id, ex);
        }
        return null;
    }

    @Override
    public void watchEvent(String eventId, EventCallback callback) {
        try {
            final String path = "/proxy/events";
            final String eventpath = "/proxy/events/" + eventId;

            LOG.info("watching {}", path);
            PathChildrenCache cache = new PathChildrenCache(client, path, true);
            // hold a strong reference to the PathChildrenCache
            watchedEvents.add(cache);
            cache.getListenable().addListener((PathChildrenCacheListener) (CuratorFramework cf, PathChildrenCacheEvent pcce) -> {
                LOG.info("ZK event {} at {}", pcce, path);
                ChildData data = pcce.getData();
                if (data != null && eventpath.equals(data.getPath())) {
                    byte[] content = data.getData();
                    LOG.info("ZK event content {}", new String(content, StandardCharsets.UTF_8));
                    if (content != null) {
                        Map<String, Object> info = MAPPER.readValue(new ByteArrayInputStream(content), Map.class);
                        String origin = info.remove("origin") + "";
                        if (peerId.equals(origin)) {
                            LOG.info("discard self originated event {}", origin);
                        } else {
                            LOG.info("handle event {}", info);
                            callback.eventFired(eventId, info);
                        }
                    }
                } else if (pcce.getType() == PathChildrenCacheEvent.Type.CONNECTION_RECONNECTED) {
                    callback.reconnected();
                }
            }, callbacksExecutor);
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void fireEvent(String eventId, Map<String, Object> data) {

        try {
            final String path = "/proxy/events/" + eventId;
            Stat exists = client.checkExists().creatingParentsIfNeeded()
                    .forPath(path);
            if (exists == null) {
                client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(path);
            }
            LOG.info("Fire event {}", path);
            Map<String, Object> info = new HashMap<>();
            info.put("origin", peerId);
            if (data != null) {
                info.putAll(data);
            }
            byte[] content = MAPPER.writeValueAsBytes(info);
            // perform an update
            client.setData()
                    .forPath(path, content);
        } catch (Exception ex) {
            LOG.error("Cannot fire event {}", eventId, ex);
        }

    }

    @Override
    public List<String> getPeers() {
        try {
            return client.getChildren().forPath("/proxy/peers");
        } catch (Exception ex) {
            LOG.error("Cannot list peers", ex);
            return Collections.emptyList();
        }
    }

    @Override
    public String describePeer(String peerId) {
        Map<String, String> info = loadInfoForPeer(peerId);
        if (info != null) {
            return peerId + " at: " + info.getOrDefault(PROPERTY_PEER_ADMIN_SERVER_HOST, "");
        }
        return peerId;
    }

    @Override
    public void stop() {
        client.close();
        callbacksExecutor.shutdown();
    }

    @Override
    public void executeInMutex(String mutexId, int timeout, Runnable runnable) {
        InterProcessMutex mutex = mutexes.computeIfAbsent(mutexId, (mId) -> {
            return new InterProcessMutex(client, "/proxy/mutex/" + mutexId);
        });
        try {
            boolean acquired = mutex.acquire(timeout, TimeUnit.SECONDS);
            if (!acquired) {
                LOG.info("Failed to acquire lock for executeInMutex (mutexId: {}, peerId: {})", mutexId, peerId);
                return;
            }
            runnable.run();
        } catch (Exception e) {
            LOG.error("Failed to acquire lock for executeInMutex (mutexId: {}, peerId: {})", mutexId, peerId, e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                LOG.error("Failed to release lock for executeInMutex (mutexId: {}, peerId: {})", mutexId, peerId, e);
            }
        }
    }

    @Override
    public void close() {
        stop();
        mutexes.clear();
    }

    @Override
    public String getLocalPeer() {
        return peerId;
    }

}
