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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.curator.ensemble.fixed.FixedEnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.carapaceproxy.cluster.GroupMembershipHandler;

/**
 * Implementation based on ZooKeeper. This class is very simple, we are not expecting heavy traffic on ZooKeeper. We
 * have two systems:
 * <ul>
 * <li>Peer discovery
 * <li>Configuration changes event broadcast
 * </ul>
 *
 * @author eolivelli
 */
public class ZooKeeperGroupMembershipHandler implements GroupMembershipHandler, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ZooKeeperGroupMembershipHandler.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CuratorFramework client;
    private final String peerId;
    private final CopyOnWriteArrayList<PathChildrenCache> watchedEvents = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, InterProcessMutex> mutexes = new ConcurrentHashMap<>();
    private final ExecutorService callbacksExecutor = Executors.newSingleThreadExecutor();

    public ZooKeeperGroupMembershipHandler(String zkAddress, int zkTimeout, String peerId) {
        client = CuratorFrameworkFactory
                .builder()
                .sessionTimeoutMs(zkTimeout)
                .waitForShutdownTimeoutMs(1000) // useful for tests
                .retryPolicy(new ExponentialBackoffRetry(1000, 2))
                .ensembleProvider(new FixedEnsembleProvider(zkAddress, true))
                .build();
        this.peerId = peerId;
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
    public void watchEvent(String eventId, EventCallback callback) {
        try {
            final String path = "/proxy/events";
            final String eventpath = "/proxy/events/" + eventId;

            LOG.info("watching " + path);
            PathChildrenCache cache = new PathChildrenCache(client, path, true);
            // hold a strong reference to the PathChildrenCache
            watchedEvents.add(cache);
            cache.getListenable().addListener((PathChildrenCacheListener) (CuratorFramework cf, PathChildrenCacheEvent pcce) -> {
                LOG.log(Level.INFO, "ZK event {0} at {1}", new Object[]{pcce, path});
                if (eventpath.equals(pcce.getData().getPath())) {
                    byte[] content = pcce.getData().getData();
                    LOG.log(Level.INFO, "ZK event content {0}", new Object[]{new String(content, StandardCharsets.UTF_8)});
                    if (content != null) {
                        Map<String, String> info = MAPPER.readValue(new ByteArrayInputStream(content), Map.class);
                        String origin = info.get("origin");
                        if (peerId.equals(origin)) {
                            LOG.log(Level.INFO, "discard self originated event " + info);
                        } else {
                            LOG.log(Level.INFO, "handle event " + info);
                            callback.eventFired(eventId);
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
    public void fireEvent(String eventId) {

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
            LOG.log(Level.INFO, "Fire event {0}", path);
            Map<String, String> info = new HashMap<>();
            info.put("origin", peerId);
            byte[] content = MAPPER.writeValueAsBytes(info);
            // perform an update
            client.setData()
                    .forPath(path, content);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Cannot fire event " + eventId, ex);
        }

    }

    @Override
    public List<String> getPeers() {
        try {
            return client.getChildren().forPath("/proxy/peers");
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Cannot list peers", ex);
            return Collections.emptyList();
        }
    }

    @Override
    public String describePeer(String peerId) {
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
                LOG.log(Level.INFO, "Failed to acquire lock for executeInMutex (mutexId: " + mutexId + ", peerId: " + peerId + ")");
                return;
            }
            runnable.run();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to acquire lock for executeInMutex (mutexId: " + mutexId + ", peerId: " + peerId + ")", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to release lock for executeInMutex (mutexId: " + mutexId + ", peerId: " + peerId + ")", e);
            }
        }
    }

    @Override
    public void close() {
        stop();
        mutexes.clear();
    }

}
