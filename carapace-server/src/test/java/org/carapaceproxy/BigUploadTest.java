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
package org.carapaceproxy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.carapaceproxy.client.EndpointKey;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.utils.TestEndpointMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.io.TempDir;

/**
 * The clients sends a big upload, and the server is very slow at draining the contents
 *
 * @author enrico.olivelli
 */
public class BigUploadTest {

    private static final Logger LOG = Logger.getLogger(BigUploadTest.class.getName());

    @TempDir
    public File tmpDir;

    public interface ClientHandler {

        public void handle(Socket client) throws Exception;

        private static File newFolder(File root, String... subDirs) throws IOException {
            String subFolder = String.join("/", subDirs);
            File result = new File(root, subFolder);
            if (!result.mkdirs()) {
                throw new IOException("Couldn't create folders " + root);
            }
            return result;
        }
    }

    public static class ConnectionResetByPeerHandler implements ClientHandler {

        @Override
        public void handle(Socket client) {
            try (Socket _client = client; // autoclose
                    InputStream in = client.getInputStream()) {
                int count = 0;
                int b = in.read();
                while (b != -1) {
                    count++;
                    if (count > 2_000) {
                        LOG.info("closing input stream after " + count);
                        in.close();
                        break;
                    }
                    b = in.read();
                }
            } catch (IOException ii) {
                LOG.log(Level.SEVERE, "error", ii);
            }
        }

        private static File newFolder(File root, String... subDirs) throws IOException {
            String subFolder = String.join("/", subDirs);
            File result = new File(root, subFolder);
            if (!result.mkdirs()) {
                throw new IOException("Couldn't create folders " + root);
            }
            return result;
        }
    }

    public static class StaticResponseHandler implements ClientHandler {

        private final byte[] response;

        public StaticResponseHandler(byte[] response) {
            this.response = response;
        }

        @Override
        public void handle(Socket client) {
            try (Socket _client = client; // autoclose
                    OutputStream out = client.getOutputStream();) {

                out.write(response);
            } catch (IOException ii) {
                LOG.log(Level.SEVERE, "error", ii);
            }
        }

        private static File newFolder(File root, String... subDirs) throws IOException {
            String subFolder = String.join("/", subDirs);
            File result = new File(root, subFolder);
            if (!result.mkdirs()) {
                throw new IOException("Couldn't create folders " + root);
            }
            return result;
        }
    }

    public static final class SimpleBlockingTcpServer implements AutoCloseable {

        private final ServerSocket socket;
        private volatile boolean closed;
        private final ExecutorService threadpool = Executors.newCachedThreadPool();
        private final Supplier<ClientHandler> factory;

        public SimpleBlockingTcpServer(Supplier<ClientHandler> factory) throws IOException {
            socket = new ServerSocket();
            this.factory = factory;
        }

        public void start() throws Exception {
            start(() -> () -> {
            });
        }

        public void start(Supplier<Runnable> beforeAccept) throws Exception {
            socket.bind(new InetSocketAddress(0));
            threadpool.submit(() -> {
                while (!closed) {
                    try {
                        beforeAccept.get().run();
                        Socket client = socket.accept();
                        LOG.log(Level.INFO, "accepted HTTP client {0}", client);
                        threadpool.submit(() -> {
                            try {
                                ClientHandler newHandler = factory.get();
                                newHandler.handle(client);
                            } catch (Exception err) {
                                LOG.log(Level.SEVERE, "error accepting client", err);
                            } finally {
                                try {
                                    client.close();
                                } catch (IOException err) {
                                }
                            }
                        });
                    } catch (Exception err) {
                        LOG.log(Level.SEVERE, "error accepting client", err);
                    }
                }
            });
        }

        public int getPort() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            threadpool.shutdown();
            if (socket != null) {
                socket.close();
            }
        }

        private static File newFolder(File root, String... subDirs) throws IOException {
            String subFolder = String.join("/", subDirs);
            File result = new File(root, subFolder);
            if (!result.mkdirs()) {
                throw new IOException("Couldn't create folders " + root);
            }
            return result;
        }
    }

    @Test
    public void testConnectionResetByPeerDuringWriteToEndpoint() throws Exception {

        try (SimpleBlockingTcpServer mockServer =
                new SimpleBlockingTcpServer(ConnectionResetByPeerHandler::new)) {

            mockServer.start();

            TestEndpointMapper mapper = new TestEndpointMapper("localhost", mockServer.getPort());
            EndpointKey key = new EndpointKey("localhost", mockServer.getPort());

            int size = 20_000_000;

            try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
                server.start();
                int port = server.getLocalPort();
                URL url = new URL("http://localhost:" + port + "/index.html");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setDoOutput(true);

                byte[] contents = "foo".getBytes(StandardCharsets.US_ASCII);
                try (OutputStream o = con.getOutputStream()) {
                    for (int i = 0; i < size; i++) {
                        o.write(contents);
                    }
                }
                assertThrows(IOException.class, () -> {
                    try (InputStream in = con.getInputStream()) {
                        IOUtils.toString(in, StandardCharsets.US_ASCII);
                    } catch (IOException ex) {
                        throw ex;
                    }
                });
                con.disconnect();
            }
        }
    }

    @Test
    public void testBlockingServerWorks() throws Exception {

        try (SimpleBlockingTcpServer mockServer =
                new SimpleBlockingTcpServer(() -> new StaticResponseHandler("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nit works!\r\n".getBytes(StandardCharsets.US_ASCII)))) {

            mockServer.start();

            TestEndpointMapper mapper = new TestEndpointMapper("localhost", mockServer.getPort());
            EndpointKey key = new EndpointKey("localhost", mockServer.getPort());

            try (HttpProxyServer server = HttpProxyServer.buildForTests("localhost", 0, mapper, newFolder(tmpDir, "junit"));) {
                server.start();
                int port = server.getLocalPort();
                URL url = new URL("http://localhost:" + port + "/index.html");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();

                try (InputStream in = con.getInputStream()) {
                    String res = IOUtils.toString(in, StandardCharsets.US_ASCII);
                    System.out.println("res:" + res);
                    assertTrue(res.contains("it works!"));
                }
                con.disconnect();
            }
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

}
