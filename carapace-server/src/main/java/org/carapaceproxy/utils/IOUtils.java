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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 *
 * @author francesco.caliumi
 */
public abstract class IOUtils {
    
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    
    public static long copyStreams(InputStream input, OutputStream output) throws IOException {
        long count = 0;
        int n = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        while (-1 != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }   
    
    /**
     * Returns a String encoded in a certain charset from the given InputStream. Do not use this method with stream
     * representing files.
     *
     * @param in
     * @param charset
     * @return
     * @throws IOException
     */
    public static String toString(InputStream in, Charset charset) throws IOException {
        if (in == null) {
            return null;
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        try (BufferedInputStream ii = new BufferedInputStream(in);
            Reader r = new InputStreamReader(ii, charset)) {
            StringWriter writer = new StringWriter();
            int c = r.read();
            while (c != -1) {
                writer.write((char) c);
                c = r.read();
            }
            return writer.toString();
        }
    }
}
