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
package org.carapaceproxy.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for tests
 *
 * @author enrico.olivelli
 */
public class TestUtils {

    private static final int MIN_PORT = 12000;
    private static final int MAX_PORT = 20000;
    private static final int MAX_PORT_ATTEMPTS = 50;
    private static final Set<Integer> HANDED_OUT_PORTS = ConcurrentHashMap.newKeySet();

    /**
     * Copies a classpath resource into the given directory, keeping its name.
     *
     * @param resource the resource to read from the context classloader
     * @param tmpDir   the directory to copy it into
     * @return the absolute path of the copy
     * @throws IOException if the resource cannot be read or written
     */
    public static String deployResource(String resource, File tmpDir) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            Path path = new File(tmpDir, resource).toPath();
            Files.copy(in, path);
            return path.toAbsolutePath().toString();
        }
    }

    /**
     * Binds an ephemeral port and releases it at once, so the number is known to be unused right now.
     * Nothing stops another process from taking it before the caller does: whoever can bind port 0 and
     * read the port back should do that instead, and this is only for a port that must stay closed.
     *
     * @return a port number nobody was listening on a moment ago
     * @throws IOException if no port could be bound
     */
    public static int getUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Picks a free port above the well known ports and below the ephemeral range, where the operating system
     * could otherwise hand the same port to a client socket between this check and the bind. Fixed low ports are
     * no good either: 7000, the HerdDB default, is taken by the AirPlay receiver on macOS. Ports already returned
     * are never returned again, as the probe socket is closed before the caller binds its own.
     *
     * @return a port that was free a moment ago, and that this method never returned before
     * @throws IOException if no free port is found
     */
    public static int getFreeNonEphemeralPort() throws IOException {
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(MIN_PORT, MAX_PORT);
            if (!HANDED_OUT_PORTS.add(port)) {
                continue;
            }
            try (ServerSocket socket = new ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (IOException taken) {
                // keep looking
            }
        }
        throw new IOException("no free port available in [" + MIN_PORT + ", " + MAX_PORT + ")");
    }
}
