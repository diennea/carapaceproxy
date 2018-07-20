package nettyhttpproxy.utils;

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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import nettyhttpproxy.EndpointStats;
import nettyhttpproxy.client.ConnectionsManagerStats;
import nettyhttpproxy.client.EndpointKey;
import org.junit.Assert;
import static org.junit.Assert.assertTrue;

/**
 * Utility for tests
 *
 * @author enrico.olivelli
 */
public class TestUtils {
    
    private static final int WAIT_FOR_CONDITION_DEFAULT_WAIT_SECONDS = 20;

    public static final Callable<Boolean> ALL_CONNECTIONS_CLOSED(ConnectionsManagerStats stats) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                for (Map.Entry<EndpointKey, EndpointStats> entry : stats.getEndpoints().entrySet()) {
                    EndpointStats es = entry.getValue();
                    int openConnections = es.getOpenConnections().intValue();
                    boolean ok = openConnections == 0;
                    if (!ok) {
                        System.out.println("Found endpoint with " + openConnections + " open connections:" + es.getKey());
                        return false;
                    }
                }
                return true;
            }
        };
    }

    public static final Callable<Boolean> NO_ACTIVE_CONNECTION(ConnectionsManagerStats stats) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                for (Map.Entry<EndpointKey, EndpointStats> entry : stats.getEndpoints().entrySet()) {
                    EndpointStats es = entry.getValue();
                    int activeConnections = es.getActiveConnections().intValue();
                    boolean ok = activeConnections == 0;
                    if (!ok) {
                        System.out.println("Found endpoint with " + activeConnections + " active connections:" + es.getKey());
                        return false;
                    }

                }
                return true;
            }
        };
    }

    public static void waitForCondition(Callable<Boolean> condition, int seconds) throws Exception {
        waitForCondition(condition, null, seconds);
    }

    public static void waitForCondition(Callable<Boolean> condition) throws Exception {
        waitForCondition(condition, null, WAIT_FOR_CONDITION_DEFAULT_WAIT_SECONDS);
    }

    public static void waitForCondition(Callable<Boolean> condition, Callable<Void> callback, int seconds) throws Exception {
        try {
            long _start = System.currentTimeMillis();
            long millis = seconds * 1000;
            while (System.currentTimeMillis() - _start <= millis) {
                if (condition.call()) {
                    return;
                }
                if (callback != null) {
                    callback.call();
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException ee) {
            Assert.fail("test interrupted!");
            return;
        } catch (Exception ee) {
            Assert.fail("error while evalutaing condition:" + ee);
            return;
        }
        Assert.fail("condition not met in time!");
    }

    public static String deployResource(String resource, File tmpDir) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);) {
            Path path = new File(tmpDir, resource).toPath();
            Files.copy(in, path);
            return path.toAbsolutePath().toString();
        }
    }

    public static int getFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0);) {
            System.out.println("Got free ephemeral port " + s.getLocalPort());
            assertTrue(s.getLocalPort() > 0);
            return s.getLocalPort();
        }
    }

}
