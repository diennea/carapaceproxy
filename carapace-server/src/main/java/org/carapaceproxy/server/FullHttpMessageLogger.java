/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.server;

import static org.stringtemplate.v4.STGroup.verbose;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * Logger for both http requests and reponses.
 *
 * @author paolo.venturi
 */
public class FullHttpMessageLogger implements Runnable, Closeable {

    private static final Logger LOG = Logger.getLogger(FullHttpMessageLogger.class.getName());
    private static final String FULL_ACCESS_LOG_PATH_SUFFIX = "full";

    private final BlockingQueue<FullHttpMessageLogEntry> queue;

    private volatile RuntimeServerConfiguration currentConfiguration;
    private volatile RuntimeServerConfiguration newConfiguration = null;
    private volatile boolean closeRequested = false;
    private volatile boolean closed = false;

    private boolean started = false;
    private final Thread thread;

    private OutputStream os = null;
    private OutputStreamWriter osw = null;
    private BufferedWriter bw = null;
    private PrintWriter pw = null;

    public long lastFlush = 0;

    private boolean accessLogAdvancedEnabled;
    private SimpleDateFormat tsFormatter;
    private int bodySize;

    public FullHttpMessageLogger(RuntimeServerConfiguration currentConfiguration) {
        this.currentConfiguration = currentConfiguration;
        this.queue = new ArrayBlockingQueue<>(this.currentConfiguration.getAccessLogMaxQueueCapacity());
        this.thread = new Thread(this);
    }

    private void ensureAccessLogFileOpened() throws IOException {
        if (os != null) {
            return;
        }

        LOG.log(Level.INFO, "Opening file: {0}", getFullAccessLogPath());
        os = new FileOutputStream(getFullAccessLogPath(), true);
        osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        bw = new BufferedWriter(osw);
        pw = new PrintWriter(bw);
    }

    private String getFullAccessLogPath() {
        return currentConfiguration.getAccessLogPath() + "." + FULL_ACCESS_LOG_PATH_SUFFIX;
    }

    private void closeAccessLogFile() throws IOException {
        LOG.log(Level.INFO, "Closing file");
        if (os != null) {
            os.close();
            os = null;
        }
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        this.newConfiguration = newConfiguration;
        accessLogAdvancedEnabled = newConfiguration.isAccessLogAdvancedEnabled();
        tsFormatter = new SimpleDateFormat(newConfiguration.getAccessLogTimestampFormat());
        bodySize = newConfiguration.getAccessLogAdvancedBodySize();
    }

    private void _reloadConfiguration() throws IOException {
        if (newConfiguration == null) {
            return;
        }

        LOG.log(Level.INFO, "Reloading conf");
        String oldAccessLogPath = this.currentConfiguration.getAccessLogPath();
        if (newConfiguration.getAccessLogMaxQueueCapacity() != currentConfiguration.getAccessLogMaxQueueCapacity()) {
            LOG.log(Level.SEVERE, "accesslog.queue.maxcapacity hot reload is not currently supported");
        }
        this.currentConfiguration = newConfiguration;
        if (!oldAccessLogPath.equals(newConfiguration.getAccessLogPath())) {
            closeAccessLogFile();
            // File opening will be retried at next cycle start
        }
        newConfiguration = null;
    }

    @Override
    public void close() {
        closeRequested = true;
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("Already started");
        }
        thread.start();
        started = true;
    }

    public void stop() {
        close();
        try {
            thread.join(60_000);
        } catch (InterruptedException ex) {
            LOG.log(Level.SEVERE, "Interrupted while stopping");
            Thread.currentThread().interrupt();
        }
    }

    void flushAccessLogFile() throws IOException {
        if (verbose) {
            LOG.log(Level.INFO, "Flushed");
        }
        if (bw != null) {
            bw.flush();
        }
        if (osw != null) {
            osw.flush();
        }
        if (os != null) {
            os.flush();
        }
        lastFlush = System.currentTimeMillis();
    }

    @Override
    public void run() {
        if (lastFlush == 0) {
            lastFlush = System.currentTimeMillis();
        }

        FullHttpMessageLogEntry currentEntry = null;
        while (!closed) {
            try {
                _reloadConfiguration();

                try {
                    ensureAccessLogFileOpened();
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "Exception while trying to open access log file");
                    LOG.log(Level.SEVERE, null, ex);
                    Thread.sleep(currentConfiguration.getAccessLogWaitBetweenFailures());
                    lastFlush = System.currentTimeMillis();
                    continue;
                }

                long waitTime = !closeRequested
                        ? currentConfiguration.getAccessLogFlushInterval() - (System.currentTimeMillis() - lastFlush)
                        : 0L;
                waitTime = Math.max(waitTime, 0L);

                if (currentEntry == null) {
                    currentEntry = queue.poll(waitTime, TimeUnit.MILLISECONDS);
                }

                if (currentEntry != null) {
                    if (verbose) {
                        LOG.log(Level.INFO, "writing entry: {0}", currentEntry);
                    }
                    currentEntry.write();
                    currentEntry = null;
                } else {
                    if (closeRequested) {
                        closeAccessLogFile();
                        closed = true;
                    }
                }

                if (System.currentTimeMillis() - lastFlush >= currentConfiguration.getAccessLogFlushInterval()) {
                    flushAccessLogFile();
                }
            } catch (InterruptedException ex) {
                LOG.log(Level.SEVERE, "Interrupt received");
                try {
                    closeAccessLogFile();
                } catch (IOException ex1) {
                    LOG.log(Level.SEVERE, null, ex1);
                }
                closed = true;
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "Exception while writing on access log file");
                LOG.log(Level.SEVERE, null, ex);
                try {
                    closeAccessLogFile();
                } catch (IOException ex1) {
                    LOG.log(Level.SEVERE, "Exception while trying to close access log file");
                    LOG.log(Level.SEVERE, null, ex1);
                }
                // File opening will be retried at next cycle start

                try {
                    Thread.sleep(currentConfiguration.getAccessLogFlushInterval());
                    lastFlush = System.currentTimeMillis();
                } catch (InterruptedException ex1) {
                    closed = true;
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void attachHandler(SocketChannel channel, AtomicReference<RequestHandler> reqHandler) {
        if (accessLogAdvancedEnabled) {
            channel.pipeline().addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
            channel.pipeline().addLast(new FullHttpMessageLoggerHandler(reqHandler));
        }
    }

    public class FullHttpMessageLoggerHandler extends SimpleChannelInboundHandler<FullHttpMessage> {

        private final AtomicReference<RequestHandler> reqHandler;

        public FullHttpMessageLoggerHandler(AtomicReference<RequestHandler> reqHandler) {
            this.reqHandler = reqHandler;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, FullHttpMessage msg) throws Exception {
            // Check for invalid http data
            if (msg.decoderResult() != DecoderResult.SUCCESS) {
                LOG.log(Level.SEVERE, "Message {0} cannot be logged due to failed decoding.", msg);
                return;
            }

            RequestHandler request = reqHandler.get();
            if (request != null) {
                FullHttpMessageLogEntry entry = new FullHttpMessageLogEntry(request, msg);
                logMessageEntry(entry);
            }
        }
    }

    public void logMessageEntry(FullHttpMessageLogEntry entry) {
        if (closeRequested) {
            LOG.log(Level.SEVERE, "Request {0} not logged to access log because RequestsLogger is closed", entry);
            return;
        }

        // If configuration reloads already created entries will keep a possibile old format, but it doesn't really matter
        boolean ret = queue.offer(entry);

        if (!ret) {
            LOG.log(Level.SEVERE, "Request {0} not logged to access log because queue is full", entry);
        }
    }

    private class FullHttpMessageLogEntry {

        private final long rid; // request id
        private final String timestamp;
        private boolean request;
        private String method;
        private String uri;
        private String statusCode;
        private final String protocolVersion;
        private final String headers;
        private final String trailingHeaders;
        private final String data;

        FullHttpMessageLogEntry(RequestHandler requestHandler, FullHttpMessage msg) {
            rid = requestHandler.getId();
            long refTime = System.currentTimeMillis();
            if (msg instanceof FullHttpRequest) {
                request = true;
                refTime = requestHandler.getStartTs();
                FullHttpRequest request = (FullHttpRequest) msg;
                method = request.method().toString();
                uri = request.uri();
            }
            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                statusCode = response.status().codeAsText() + "";
            }
            timestamp = tsFormatter.format(new Timestamp(refTime));
            protocolVersion = msg.protocolVersion().toString();
            headers = formatHeaders(msg.headers());
            trailingHeaders = formatHeaders(msg.trailingHeaders());
            ByteBuf data = msg.content();
            this.data = data.toString(StandardCharsets.UTF_8);
        }

        void write() throws IOException {
            String opening = ">>>>>>>>>>>>>>>>>>>>[ SERVER RESPONSE (rid: " + rid + ") ]>>>>>>>>>>>>>>>>>>>>";
            String closing = ">>>>>>>>>>>>>>>>>>>>[ SERVER RESPONSE END (rid: " + rid + ") ]>>>>>>>>>>>>>>>>>>>>";
            if (request) {
                opening = "<<<<<<<<<<<<<<<<<<<<[ CLIENT REQUEST (rid: " + rid + ") ]<<<<<<<<<<<<<<<<<<<<";
                closing = "<<<<<<<<<<<<<<<<<<<<[ CLIENT REQUEST END (rid: " + rid + ") ]<<<<<<<<<<<<<<<<<<<<";
            }
            pw.println(opening);
            pw.println("Timestamp: " + timestamp);
            pw.println("HTTP Version: " + protocolVersion);
            if (request) {
                pw.println("HTTP Method: " + method);
                pw.println("URI: " + uri);
            } else {
                pw.println("Status code: " + statusCode);
            }
            pw.println("--------------------[ HEADERS ]--------------------");
            pw.println(headers);
            if (!trailingHeaders.isEmpty()) {
                pw.println(trailingHeaders);
            }
            if (data != null && !data.isEmpty()) {
                pw.println("--------------------[ BODY ]--------------------");
                pw.println(bodySize > 0 ? data.substring(0, Math.min(data.length(), bodySize)) : data);
            }
            pw.println(closing);
            pw.println("\n");
            pw.flush();
        }

        private String formatHeaders(HttpHeaders headers) {
            if (headers.isEmpty()) {
                return "";
            }

            StringBuilder sBuilder = new StringBuilder();
            headers.forEach(h -> {
                sBuilder.append(h.getKey());
                sBuilder.append(": ");
                sBuilder.append(h.getValue());
                sBuilder.append("\n");
            });
            String res = sBuilder.toString();
            return res.substring(0, res.length() - 1);
        }

        @Override
        public String toString() {
            return "FullHttpMessageLogEntry{" + "rid=" + rid + ", timestamp=" + timestamp + ", request=" + request + ", method=" + method + ", uri=" + uri + ", statusCode=" + statusCode + '}';
        }

    }
}
