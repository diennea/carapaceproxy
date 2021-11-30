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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Raw Socket based Http Client, very useful to reproduce weird behaviours
 */
public final class RawHttpClient implements AutoCloseable {

    private static final boolean DEBUG = false;
    private static final int SOCKET_SO_TIMEOUT = 10_000; // millis

    private final Socket socket;
    private final String host;

    public RawHttpClient(String host, int port) throws IOException {
        this(host, port, false);
    }

    public RawHttpClient(String host, int port, boolean ssl) throws IOException {
        this(host, port, ssl, null, null);
    }

    public RawHttpClient(String host, int port, boolean ssl, String sniHostname) throws IOException {
        this(host, port, ssl, sniHostname, null);
    }

    public RawHttpClient(String host, int port, boolean ssl, String sniHostname, String[] sslProtocols) throws IOException {
        this.host = host;
        if (ssl) {
            SSLSocketFactory factory = HttpTestUtils.getSocket_factory();
            socket = factory.createSocket();
            SSLSocket sslSocket = (SSLSocket) socket;
            if (sslProtocols != null) {
                sslSocket.setEnabledProtocols(sslProtocols);
            }
            if (sniHostname != null) {
                SSLParameters sslParameters = new SSLParameters();
                List<SNIServerName> sniHostNames = new ArrayList<>();
                sniHostNames.add(new SNIHostName(sniHostname));
                sslParameters.setServerNames(sniHostNames);
                sslSocket.setSSLParameters(sslParameters);
            }
            socket.connect(new InetSocketAddress(host, port));
        } else {
            socket = new Socket(host, port);
        }
        socket.setSoTimeout(SOCKET_SO_TIMEOUT);
    }

    public static RawHttpClient withEnabledSSLProtocols(String host, int port, String... protocols) throws IOException {
        return new RawHttpClient(host, port, true, null, protocols);
    }

    public Certificate[] getServerCertificate() throws SSLPeerUnverifiedException {
        SSLSocket sSLSocket = (SSLSocket) socket;
        return sSLSocket.getSession().getPeerCertificates();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public HttpResponse get(String uri) throws IOException {
        return executeRequest("GET " + uri + " HTTP/1.1"
                + "\r\n" + HttpHeaderNames.HOST + ": " + host
                + "\r\n" + HttpHeaderNames.CONNECTION + ": " + HttpHeaderValues.KEEP_ALIVE
                + "\r\n\r\n");
    }

    public HttpResponse get(String uri, BasicAuthCredentials credentials) throws IOException {
        return executeRequest("GET " + uri + " HTTP/1.1"
                + "\r\n" + HttpHeaderNames.HOST + ": " + host
                + "\r\n" + HttpHeaderNames.CONNECTION + ": " + HttpHeaderValues.KEEP_ALIVE
                + "\r\nAuthorization: Basic " + credentials.toBase64()
                + "\r\n\r\n");
    }

    public void sendRequest(String request) throws IOException {
        sendRequest(request.getBytes(StandardCharsets.UTF_8));
    }

    public void sendRequest(byte[] request) throws IOException {
        OutputStream oo = socket.getOutputStream();
        oo.write(request);
        oo.flush();
    }

    private static class BufferedStream extends OutputStream {

        final OutputStream wrapped;
        final ByteArrayOutputStream buffer;

        public BufferedStream(OutputStream wrapped) {
            this.wrapped = wrapped;
            this.buffer = new ByteArrayOutputStream();
        }

        @Override
        public void write(int b) throws IOException {
            wrapped.write(b);
            buffer.write(b);
        }

        @Override
        public String toString() {
            try {
                return "BufferedStream{buffer=" + buffer.toString("utf-8") + '}';
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public static final class BasicAuthCredentials {

        String username;
        String password;

        public BasicAuthCredentials(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String toBase64() throws UnsupportedEncodingException {
            String credentials = (username + ":" + password);
            return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

    }

    public static final class HttpResponse {

        ByteArrayOutputStream rawResponse = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<String> headerLines = new ArrayList<>();
        String statusLine;
        String transferEncoding;
        int expectedContentLength = -1;

        public int getExpectedContentLength() {
            return expectedContentLength;
        }

        public String getTransferEncoding() {
            return transferEncoding;
        }

        public List<String> getHeaderLines() {
            return headerLines;
        }

        public String getStatusLine() {
            return statusLine;
        }

        public byte[] getFull() {
            return rawResponse.toByteArray();
        }

        public byte[] getBody() {
            return body.toByteArray();
        }

        public String getBodyString() throws UnsupportedEncodingException {
            return body.toString("utf-8");
        }

        @Override
        public String toString() {
            try {
                return rawResponse.toString("utf-8");
            } catch (UnsupportedEncodingException ex) {
                throw new RuntimeException(ex);
            }
        }

    }

    private static void println(Object msg) {
        if (DEBUG) {
            System.out.println(msg);
        }
    }

    public static HttpResponse consumeHttpResponseInput(final InputStream in) throws IOException {

        HttpResponse result = new HttpResponse();

        BufferedStream firstLine = new BufferedStream(result.rawResponse);
        consumeLFEndedLine(in, firstLine, true);
        result.statusLine = firstLine.buffer.toString("utf-8");
        println("[STATUSLINE] " + result.statusLine);

        if (!result.statusLine.startsWith("HTTP/1.1 ")) {
            throw new IOException("bad response, does not start with HTTP/1.1. Received: " + result.statusLine);
        }

        if (!result.statusLine.startsWith("HTTP/1.1 ")) {
            throw new IOException("bad response, does not start with HTTP/1.1. Received: " + result.statusLine);
        }

        // header
        while (true) {
            BufferedStream counter = new BufferedStream(result.rawResponse);
            consumeLFEndedLine(in, counter, false);
            String line = counter.buffer.toString("utf-8");
            println("READ HEADER " + line.trim() + " size " + counter.buffer.size());
            if (counter.buffer.size() <= 2) {
                println("END OF HEADER");
                // end of header
                break;
            } else {

                result.headerLines.add(line);
                if (line.toLowerCase().startsWith("content-length: ")) {
                    result.expectedContentLength = Integer.parseInt(line.toLowerCase().substring("content-length: ".length()).trim());
                    println("expectedContentLength:" + result.expectedContentLength);
                }
                if (line.toLowerCase().startsWith("transfer-encoding: ")) {
                    result.transferEncoding = line.toLowerCase().substring("transfer-encoding: ".length()).trim();
                }
            }
        }
        if (result.expectedContentLength == 0) {
            println("END OF BODY content-len 0");
            return result;
        }
        if (result.statusLine.startsWith("HTTP/1.1 304 ")) {
            // not modified
            return result;
        }
        if (result.statusLine.startsWith("HTTP/1.1 100 Continue")) {
            // continue
            return result;
        }
        boolean chunked = "chunked".equals(result.transferEncoding);
        if (chunked) {
            DataInputStream dataIn = new DataInputStream(in);
            while (true) {
                String line = readASCIILine(dataIn, true);
                result.rawResponse.write(line.getBytes("ASCII"));
                println("CHUNK SIZE " + line + "(hex)");
                int size = Integer.parseInt(line.trim(), 16);
                if (size == 0) {
                    // last chunk
                    byte[] eol = new byte[2];
                    dataIn.readFully(eol);
                    result.rawResponse.write(eol);
                    if (eol[0] != '\r' || eol[1] != '\n') {
                        throw new IOException("unexpected delimiter " + new String(eol, "ASCII"));
                    }
                    break;
                }
                byte[] chunk = new byte[size];
                dataIn.readFully(chunk);

                result.rawResponse.write(chunk);
                result.body.write(chunk);
                println("READ " + new String(chunk, "ASCII"));

                byte[] eol = new byte[2];
                dataIn.readFully(eol);
                result.rawResponse.write(eol);
                if (eol[0] != '\r' || eol[1] != '\n') {
                    throw new IOException("unexpected delimiter " + new String(eol, "ASCII"));
                }
            }
        } else {
            // body
            int b = in.read();
            while (b != -1) {
                result.rawResponse.write(b);
                result.body.write(b);

                if (result.expectedContentLength > 0 && result.body.size() == result.expectedContentLength) {
                    println("END OF BODY content-len " + result.expectedContentLength);
                    return result;
                }

                b = in.read();
            }
            if (result.expectedContentLength > 0 && result.body.size() < result.expectedContentLength) {
                throw new IOException("Incomplete response, expected Content-Length: " + result.expectedContentLength + ", but read only " + result.body.size());
            }
        }

        println("END OF BODY");
        return result;
    }

    private static String readASCIILine(final InputStream in, boolean errorIfEmpty) throws IOException {
        ByteArrayOutputStream oo = new ByteArrayOutputStream();
        consumeLFEndedLine(in, oo, errorIfEmpty);
        return oo.toString("ASCII");
    }

    private static void consumeLFEndedLine(final InputStream in, OutputStream responseReceived, boolean errorIfEmpty) throws IOException {
        int b = in.read();
        if (b == -1 && errorIfEmpty) {
            throw new IOException("unexpected end of input stream");
        }
        while (b != -1) {
            responseReceived.write(b);
            if (b == '\n') {
                break;
            }
            b = in.read();
        }
    }

    public HttpResponse executeRequest(ByteArrayInputStream bais) throws IOException {
        sendRequest(bais.readAllBytes());
        return consumeHttpResponseInput(socket.getInputStream());
    }

    public HttpResponse executeRequest(String request) throws IOException {
        sendRequest(request.getBytes(StandardCharsets.UTF_8));
        return consumeHttpResponseInput(socket.getInputStream());
    }

    public Socket getSocket() {
        return socket;
    }

    public SSLSocket getSSLSocket() {
        return (SSLSocket) socket;
    }

    public HttpResponse readResponse() throws IOException {
        return consumeHttpResponseInput(socket.getInputStream());
    }

}
