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
package org.carapaceproxy.core;

import com.google.common.annotations.VisibleForTesting;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stringtemplate.v4.NoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STWriter;

/**
 *
 * @author francesco.caliumi
 */
public class RequestsLogger implements Runnable, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RequestsLogger.class);

    private final BlockingQueue<Entry> queue;

    private volatile RuntimeServerConfiguration currentConfiguration;
    private volatile RuntimeServerConfiguration newConfiguration = null;
    private volatile boolean closeRequested = false;
    private volatile boolean closed = false;

    private boolean started = false;
    private final Thread thread;

    private OutputStream os = null;
    private OutputStreamWriter osw = null;
    private BufferedWriter bw = null;
    private STWriter stw = null;

    public long lastFlush = 0;

    private boolean verbose = false;
    private boolean breakRunForTests = false;

    private DateTimeFormatter accessLogTimestampFormatter;

    public RequestsLogger(RuntimeServerConfiguration currentConfiguration) {
        this.currentConfiguration = currentConfiguration;
        this.queue = new ArrayBlockingQueue<>(this.currentConfiguration.getAccessLogMaxQueueCapacity());
        this.thread = new Thread(this);
        this.accessLogTimestampFormatter = buildTimestampFormatter(currentConfiguration);
    }

    private static DateTimeFormatter buildTimestampFormatter(final RuntimeServerConfiguration currentConfiguration) {
        return DateTimeFormatter
                .ofPattern(currentConfiguration.getAccessLogTimestampFormat())
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault());
    }

    private void ensureAccessLogFileOpened() throws IOException {
        if (os != null) {
            return;
        }

        if (verbose) {
            LOG.info("Opening file: {}", currentConfiguration.getAccessLogPath());
        }
        os = new FileOutputStream(currentConfiguration.getAccessLogPath(), true);
        osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        bw = new BufferedWriter(osw);
        stw = new NoIndentWriter(bw);
    }

    @VisibleForTesting
    void flushAccessLogFile() throws IOException {
        if (verbose) {
            LOG.info("Flushed");
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

    private void closeAccessLogFile() throws IOException {
        if (verbose) {
            LOG.info("Closing file");
        }
        if (stw != null) {
            stw = null;
        }
        if (bw != null) {
            bw.close();
            bw = null;
        }
        if (osw != null) {
            osw.close();
            osw = null;
        }
        if (os != null) {
            os.close();
            os = null;
        }
    }

    @VisibleForTesting
    void rotateAccessLogFile() {
        String accesslogPath = this.currentConfiguration.getAccessLogPath();
        long maxSize = this.currentConfiguration.getAccessLogMaxSize();
        DateFormat date = new SimpleDateFormat("yyyy-MM-dd-ss");
        String newAccessLogName = accesslogPath + "-" + date.format(new Date());

        Path currentAccessLogPath = Paths.get(accesslogPath);
        Path newAccessLogPath = Paths.get(newAccessLogName);

        try (FileChannel logFileChannel = FileChannel.open(currentAccessLogPath)) {
            long currentSize = logFileChannel.size();
            if (currentSize >= maxSize && maxSize > 0) {
                LOG.info("Maximum access log size reached. file: {} , Size: {} , maxSize: {}", accesslogPath, currentSize, maxSize);
                Files.move(currentAccessLogPath, newAccessLogPath, StandardCopyOption.ATOMIC_MOVE);
                closeAccessLogFile();
                // File opening will be retried at next cycle start

                //Zip old file
                gzipFile(newAccessLogName, newAccessLogName + ".gzip", true);
            }
        } catch (IOException e) {
            LOG.error("Error: Unable to rename file {} in {}: ", accesslogPath, newAccessLogName, e);
        }
    }

    private void gzipFile(String source_filepath, String destination_zip_filepath, boolean deleteSource) {
        byte[] buffer = new byte[1024];
        File source = new File(source_filepath);
        File dest = new File(destination_zip_filepath);

        try (FileOutputStream fileOutputStream = new FileOutputStream(dest)) {
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fileOutputStream)) {
                try (FileInputStream fileInput = new FileInputStream(source)) {
                    int bytes_read;

                    while ((bytes_read = fileInput.read(buffer)) > 0) {
                        gzipOutputStream.write(buffer, 0, bytes_read);
                    }
                }
                gzipOutputStream.finish();
                gzipOutputStream.close();

                //delete uncompressed file
                if (deleteSource && dest.exists()) {
                    source.delete();
                }
            }
            if (verbose) {
                LOG.info("{} was compressed successfully", source_filepath);
            }
        } catch (IOException ex) {
            LOG.error("{} Compression failed", source_filepath, ex);
        }
    }

    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        this.newConfiguration = newConfiguration;
    }

    private void reloadConfiguration() throws IOException {
        if (newConfiguration == null) {
            return;
        }

        if (verbose) {
            LOG.info("Reloading conf");
        }
        String oldAccessLogPath = this.currentConfiguration.getAccessLogPath();
        if (newConfiguration.getAccessLogMaxQueueCapacity() != currentConfiguration.getAccessLogMaxQueueCapacity()) {
            LOG.error("accesslog.queue.maxcapacity hot reload is not currently supported");
        }
        this.currentConfiguration = newConfiguration;
        if (!oldAccessLogPath.equals(newConfiguration.getAccessLogPath())) {
            closeAccessLogFile();
            // File opening will be retried at next cycle start
        }
        this.accessLogTimestampFormatter = buildTimestampFormatter(this.currentConfiguration);
        newConfiguration = null;
    }

    public void logRequest(ProxyRequest request) {
        Entry entry = new Entry(request, currentConfiguration.getAccessLogFormat(), accessLogTimestampFormatter);

        if (closeRequested) {
            LOG.error("Request {} not logged to access log because RequestsLogger is closed", entry.render());
            return;
        }

        // If configuration reloads already created entries will keep a possibile old format, but it doesn't really matter
        boolean ret = queue.offer(entry);

        if (!ret) {
            LOG.error("Request {} not logged to access log because queue is full", entry.render());
        }
    }

    @VisibleForTesting
    void setBreakRunForTests(boolean breakRunForTests) {
        this.breakRunForTests = breakRunForTests;
    }

    @VisibleForTesting
    void setVerbose(boolean verbose) {
        this.verbose = verbose;
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
            LOG.error("Interrupted while stopping");
        }
    }

    @Override
    public void run() {
        if (lastFlush == 0) {
            lastFlush = System.currentTimeMillis();
        }

        Entry currentEntry = null;
        while (!closed) {
            try {
                reloadConfiguration();

                try {
                    ensureAccessLogFileOpened();
                } catch (IOException ex) {
                    LOG.error("Exception while trying to open access log file", ex);
                    Thread.sleep(currentConfiguration.getAccessLogWaitBetweenFailures());
                    lastFlush = System.currentTimeMillis();
                    if (breakRunForTests) {
                        break;
                    }
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
                        LOG.info("writing entry: {}", currentEntry.render());
                    }
                    currentEntry.write(stw, bw);
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
                //Check if is time to rotate
                rotateAccessLogFile();

            } catch (InterruptedException ex) {
                LOG.error("Interrupt received");
                try {
                    closeAccessLogFile();
                } catch (IOException ex1) {
                    LOG.error("Failed to close access log after interrupt", ex1);
                }
                closed = true;

            } catch (IOException ex) {
                LOG.error("Exception while writing on access log file", ex);
                try {
                    closeAccessLogFile();
                } catch (IOException ex1) {
                    LOG.error("Exception while trying to close access log file", ex1);
                }
                // File opening will be retried at next cycle start

                try {
                    Thread.sleep(currentConfiguration.getAccessLogFlushInterval());
                    lastFlush = System.currentTimeMillis();
                } catch (InterruptedException ex1) {
                    closed = true;
                }
            }

            if (breakRunForTests) {
                break;
            }
        }
    }

    /*
     * ----------------------------------------------------------------------------------------------------
     */

 /*
     * Entry handled fields: <client_ip>: client ip address <server_ip>: local httpproxy listener <method>: http method <host>: host header of the http request <uri>: uri requested in the http request
     * <timestamp>: when httpproxy started to serving the request <backend_time>: milliseconds from request start to the first byte received from the backend <total_time>: milliseconds from request
     * start to the last byte sended to client (tcp delays are not counted) <action_id>: action id (PROXY, CACHE, ...) <route_id>: id of the route used for selecting action and backend <backend_id>:
     * id (host+port) of the backend to which the request was forwarded <user_id>: user id inferred by filters <session_id>: session id inferred by filters <tls_protocol>: tls protocol used
     * <tls_cipher_suite>: cipher suite used
     * <http_protocol_version>: http protocol used
     */
    static final class Entry {

        private final ST format;

        public Entry(final ProxyRequest request, final String format, final DateTimeFormatter tsFormatter) {
            this.format = new ST(format);

            this.format.add("client_ip", request.getRemoteAddress().getAddress().getHostAddress());
            this.format.add("server_ip", request.getLocalAddress().getAddress().getHostAddress());
            this.format.add("method", request.getRequest().method().name());
            this.format.add("host", request.getRequest().requestHeaders().getAsString(HttpHeaderNames.HOST));
            this.format.add("uri", request.getUri());
            this.format.add("timestamp", tsFormatter.format(Instant.ofEpochMilli(request.getStartTs())));
            this.format.add("total_time", request.getLastActivity() - request.getStartTs());
            this.format.add("action_id", request.getAction().getAction());
            this.format.add("route_id", request.getAction().getRouteId());
            this.format.add("user_id", request.getUserId());
            this.format.add("session_id", request.getSessionId());
            this.format.add("http_protocol_version", request.getRequest().version());
            if (request.isServedFromCache()) {
                this.format.add("backend_id", "CACHED");
                this.format.add("backend_time", "0");
            } else {
                this.format.add("backend_id", String.format("%s:%s", request.getAction().getHost(), request.getAction().getPort()));
                this.format.add("backend_time", request.getBackendStartTs() - request.getStartTs());
            }
            formatSSLProperties(request);
        }

        private void formatSSLProperties(ProxyRequest request) {
            String sslProtocol = request.getSslProtocol();
            String cipherSuite = request.getCipherSuite();
            this.format.add("tls_protocol", sslProtocol != null ? sslProtocol : "n/a");
            this.format.add("tls_cipher_suite", cipherSuite != null ? cipherSuite : "n/a");
        }

        @VisibleForTesting
        String render() {
            return this.format.render(Locale.ITALY);
        }

        public void write(STWriter stw, BufferedWriter bw) throws IOException {
            this.format.write(stw, Locale.ITALY);
            bw.write("\n");
        }
    }
}
