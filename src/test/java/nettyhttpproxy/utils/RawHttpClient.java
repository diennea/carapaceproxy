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
package nettyhttpproxy.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Raw Socket based Http Client, very useful to reproduce weird behaviours
 */
public final class RawHttpClient {

    public static void executeHttpRequest(String host, int port, byte[] request, OutputStream responseReceived) throws IOException {
        try (Socket socket = new Socket(host, port);
            OutputStream oo = socket.getOutputStream();
            InputStream in = socket.getInputStream()) {
            oo.write(request);
            oo.flush();
            int b = in.read();
            while (b != -1) {
                char c = (char) b;
//                System.out.println("Received: " + c);
                responseReceived.write(b);
                b = in.read();
            }
        }
    }

    public static void sendOnlyHttpRequestAndClose(String host, int port, byte[] request) throws IOException {
        try (Socket socket = new Socket(host, port);
            OutputStream oo = socket.getOutputStream();
            InputStream in = socket.getInputStream()) {
            oo.write(request);
            oo.flush();
        }
    }

    public static String executeHttpRequest(String host, int port, String request) throws IOException {
        ByteArrayOutputStream oo = new ByteArrayOutputStream();
        executeHttpRequest(host, port, request.getBytes(StandardCharsets.UTF_8), oo);
        return oo.toString("utf-8");
    }
}
