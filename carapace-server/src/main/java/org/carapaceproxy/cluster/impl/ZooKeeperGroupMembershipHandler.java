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

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.bookkeeper.zookeeper.ExponentialBackoffRetryPolicy;
import org.apache.curator.ensemble.fixed.FixedEnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.carapaceproxy.cluster.GroupMembershipHandler;

/**
 * Implementation based on ZooKeeper
 *
 * @author eolivelli
 */
public class ZooKeeperGroupMembershipHandler implements GroupMembershipHandler, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ZooKeeperGroupMembershipHandler.class.getName());

    private final CuratorFramework client;

    public ZooKeeperGroupMembershipHandler(String zkAddress, int zkTimeout) {
        client = CuratorFrameworkFactory
                .builder()
                .sessionTimeoutMs(zkTimeout)
                .waitForShutdownTimeoutMs(1000) // useful for tests
                .retryPolicy(new ExponentialBackoffRetry(1000, 2))
                .ensembleProvider(new FixedEnsembleProvider(zkAddress, true))
                .build();
    }

    @Override
    public void start(String peerID) {
        try {
            client.start();
            Stat exists = client.checkExists().creatingParentsIfNeeded()
                    .forPath("/proxy/peers/" + peerID);
            if (exists == null) {
                client.create()
                        .creatingParentsIfNeeded()
                        .forPath("/proxy/peers/" + peerID);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void watchEvent(String eventId, EventCallback callback) {
        try {
            final String path = "/proxy/events/" + eventId;

            client.create()
                    .creatingParentsIfNeeded()
                    .forPath(path);

            client.getData().usingWatcher(new CuratorWatcher() {
                @Override
                public void process(WatchedEvent we) throws Exception {
                    LOG.log(Level.INFO, "ZK event {0} at {1}", new Object[]{we, path});
                    callback.eventFired(eventId);
                }
            }).forPath(path);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void fireEvent(String eventId) {

        try {
            final String path = "/proxy/events/" + eventId;
            LOG.log(Level.INFO, "Fire event {0}", path);
            client.setData()
                    .forPath(path);
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
    }

    @Override
    public void close() {
        stop();
    }

}
