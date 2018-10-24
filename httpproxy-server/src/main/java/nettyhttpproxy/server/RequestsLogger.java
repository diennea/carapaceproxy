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
package nettyhttpproxy.server;

import com.google.common.annotations.VisibleForTesting;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import nettyhttpproxy.server.cache.ContentsCache;
import org.stringtemplate.v4.NoIndentWriter;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STWriter;

/**
 *
 * @author francesco.caliumi
 */
public class RequestsLogger implements Runnable, Closeable {
    
    private static final Logger LOG = Logger.getLogger(ContentsCache.class.getName());
    
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
    
    public RequestsLogger(RuntimeServerConfiguration currentConfiguration) {
        this.currentConfiguration = currentConfiguration;
        this.queue = new ArrayBlockingQueue<>(this.currentConfiguration.getAccessLogMaxQueueCapacity());
        this.thread = new Thread(this);
    }
    
    private void ensureAccessLogFileOpened() throws IOException {
        if (os != null) {
            return;
        }
        
        if (verbose) {
            LOG.log(Level.INFO, "Opening file: {0}", currentConfiguration.getAccessLogPath());
        }
        os = new FileOutputStream(currentConfiguration.getAccessLogPath(), true);
        osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        bw = new BufferedWriter(osw);
        stw = new NoIndentWriter(bw);
    }
    
    @VisibleForTesting
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
    
    private void closeAccessLogFile() throws IOException {
        if (verbose) {
            LOG.log(Level.INFO, "Closing file");
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
    
    public void reloadConfiguration(RuntimeServerConfiguration newConfiguration) {
        this.newConfiguration = newConfiguration;
    }
    
    private void _reloadConfiguration() throws IOException {
        if (newConfiguration == null) {
            return;
        }
        
        if (verbose) {
            LOG.log(Level.INFO, "Reloading conf");
        }
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
    
    public void logRequest(RequestHandler request) {
        Entry entry = new Entry(
            request, currentConfiguration.getAccessLogFormat(), currentConfiguration.getAccessLogTimestampFormat());
        
        if (closeRequested) {
            LOG.log(Level.SEVERE, "Request {0} not logged to access log because RequestsLogger is closed", entry.render());
            return;
        }
        
        // If configuration reloads already created entries will keep a possibile old format, but it doesn't really matter
        boolean ret = queue.offer(entry);
        
        if (!ret) {
            LOG.log(Level.SEVERE, "Request {0} not logged to access log because queue is full", entry.render());
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
            LOG.log(Level.SEVERE, "Interrupted while stopping");
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
                _reloadConfiguration();
                
                try {
                    ensureAccessLogFileOpened();
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "Exception while trying to open access log file");
                    LOG.log(Level.SEVERE, null, ex);
                    Thread.sleep(currentConfiguration.getAccessLogWaitBetweenFailures());
                    lastFlush = System.currentTimeMillis();
                    if (breakRunForTests) {
                        break;
                    }
                    continue;
                }
                
                long waitTime = !closeRequested ? 
                    currentConfiguration.getAccessLogFlushInterval() - (System.currentTimeMillis() - lastFlush) :
                    0L;
                waitTime = Math.max(waitTime, 0L);
                
                if (currentEntry == null) {
                    currentEntry = queue.poll(waitTime, TimeUnit.MILLISECONDS);
                }
                
                if (currentEntry != null) {
                    if (verbose) {
                        LOG.log(Level.INFO, "writing entry: {0}", currentEntry.render());
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
                
            } catch (InterruptedException ex) {
                LOG.log(Level.SEVERE, "Interrupt received");
                try {
                    closeAccessLogFile();
                } catch (IOException ex1) {
                    LOG.log(Level.SEVERE, null, ex1);
                }
                closed = true;
                
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
                }
            }
            
            if (breakRunForTests) {
                break;
            }
        }
    }
    
    /* ---------------------------------------------------------------------------------------------------- */
    
    /*
        Entry handled fields:
            <client_ip>: client ip address
            <server_ip>: local httpproxy listener
            <method>: http method
            <host>: host header of the http request
            <uri>: uri requested in the http request
            <timestamp>: when httpproxy started to serving the request 
            <backend_time>: milliseconds from request start to the first byte received from the backend
            <total_time>: milliseconds from request start to the last byte sended to client (tcp delays are not counted)
            <action_id>: action id (PROXY, CACHE, ...)
            <route_id>: id of the route used for selecting action and backend
            <backend_id>: id (host+port) of the backend to which the request was forwarded
            <user_id>: user id inferred by filters
            <session_id>: session id inferred by filters
    */
    static final class Entry {
        
        private final ST format;

        public Entry(RequestHandler request, String format, String timestampFormat) {
            SimpleDateFormat tsFormatter = new SimpleDateFormat(timestampFormat);

            this.format = new ST(format);

            this.format.add("client_ip", request.getRemoteAddress().getAddress().getHostAddress());
            this.format.add("server_ip", request.getLocalAddress().getAddress().getHostAddress());
            this.format.add("method", request.getRequest().method().name());
            this.format.add("host", request.getRequest().headers().getAsString(HttpHeaderNames.HOST));
            this.format.add("uri", request.getUri());
            this.format.add("timestamp", tsFormatter.format(new Timestamp(request.getStartTs())));
            this.format.add("total_time", request.getLastActivity() - request.getStartTs());
            this.format.add("action_id", request.getAction().action);
            this.format.add("route_id", request.getAction().routeid);
            this.format.add("user_id", request.getUserId());
            this.format.add("session_id", request.getSessionId());
            if (!request.isServedFromCache()) {
                this.format.add("backend_id", String.format("%s:%s", request.getAction().host, request.getAction().port));
                this.format.add("backend_time", request.getBackendStartTs() - request.getStartTs());
            } else {
                this.format.add("backend_id", "CACHED");
                this.format.add("backend_time", "0");
            }
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
