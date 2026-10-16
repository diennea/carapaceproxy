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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.junit.Test;

public class NullGroupMembershipHandlerTest {

    @Test
    public void testStoreWriteLock() throws Exception {
        NullGroupMembershipHandler handler = new NullGroupMembershipHandler();
        try (GroupMembershipHandler.StoreWriteLock lock = handler.acquireStoreWriteLock(1, TimeUnit.SECONDS)) {
            lock.ensureHeld();
            try (GroupMembershipHandler.StoreWriteLock nested = handler.acquireStoreWriteLock(1, TimeUnit.SECONDS)) {
                nested.ensureHeld();
            }
            CompletableFuture<Void> other = CompletableFuture.runAsync(() ->
                    assertThrows(TimeoutException.class, () -> handler.acquireStoreWriteLock(100, TimeUnit.MILLISECONDS)));
            other.get(10, TimeUnit.SECONDS);
        }
        CompletableFuture.runAsync(() -> {
            try (GroupMembershipHandler.StoreWriteLock lock = handler.acquireStoreWriteLock(1, TimeUnit.SECONDS)) {
                lock.ensureHeld();
            } catch (TimeoutException | InterruptedException e) {
                throw new AssertionError(e);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void testExecuteInMutex() throws Exception {
        NullGroupMembershipHandler handler = new NullGroupMembershipHandler();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> handler.executeInMutex("m", 1, () -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        AtomicBoolean ran = new AtomicBoolean();
        handler.executeInMutex("m", 1, () -> ran.set(true));
        assertFalse(ran.get()); // timed out, skipped
        handler.executeInMutex("other", 1, () -> ran.set(true));
        assertTrue(ran.get()); // different id, no contention

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        ran.set(false);
        handler.executeInMutex("m", 1, () -> ran.set(true));
        assertTrue(ran.get());
    }
}
