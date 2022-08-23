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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * Handles error pages loading
 */
public class StaticContentsManager {

    // Loadable resource types
    public static final String CLASSPATH_RESOURCE = "classpath:";
    public static final String FILE_RESOURCE = "file:";
    public static final String IN_MEMORY_RESOURCE = "mem:";

    public static final String DEFAULT_NOT_FOUND = CLASSPATH_RESOURCE + "/default-error-pages/404_notfound.html";
    public static final String DEFAULT_INTERNAL_SERVER_ERROR = CLASSPATH_RESOURCE + "/default-error-pages/500_internalservererror.html";
    public static final String DEFAULT_MAINTENANCE_MODE_PAGE =  CLASSPATH_RESOURCE + "/default-error-pages/500_maintenance.html";

    private static final Logger LOG = Logger.getLogger(StaticContentsManager.class.getName());

    private final ConcurrentHashMap<String, ByteBuf> contents = new ConcurrentHashMap<>();

    public void close() {
        contents.values().forEach(ByteBuf::release);
        contents.clear();
    }

    public DefaultFullHttpResponse buildServiceNotAvailableResponse() {
        return buildResponse(500, DEFAULT_INTERNAL_SERVER_ERROR);
    }

    public DefaultFullHttpResponse buildResponse(int code, String resource) {
        if (code <= 0) {
            code = 500;
        }
        DefaultFullHttpResponse res;
        ByteBuf content = resource == null ? null : contents.computeIfAbsent(resource, StaticContentsManager::loadResource);
        if (content != null) {
            content = content.retainedDuplicate();
            int contentLength = content.readableBytes();
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code), content);
            res.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        } else {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(404));
        }
        res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        return res;
    }

    private static ByteBuf loadResource(String resource) {
        try {
            if (resource.startsWith(CLASSPATH_RESOURCE)) {
                resource = resource.substring(CLASSPATH_RESOURCE.length());
                try (InputStream resourceAsStream = StaticContentsManager.class.getResourceAsStream(resource);) {
                    if (resourceAsStream == null) {
                        throw new FileNotFoundException("no such resource " + resource + " on classpath");
                    }
                    return Unpooled.wrappedBuffer(IOUtils.toByteArray(resourceAsStream));
                }
            } else if (resource.startsWith(FILE_RESOURCE)) {
                resource = resource.substring(FILE_RESOURCE.length());
                return Unpooled.wrappedBuffer(FileUtils.readFileToByteArray(new File(resource)));
            } else if (resource.startsWith(IN_MEMORY_RESOURCE)) {
                resource = resource.substring(IN_MEMORY_RESOURCE.length());
                return Unpooled.wrappedBuffer(resource.getBytes("UTF-8"));
            } else {
                throw new IOException("cannot load resource " + resource + ", path must start with " + FILE_RESOURCE + " or " + CLASSPATH_RESOURCE);
            }
        } catch (IOException | NullPointerException err) {
            LOG.log(Level.SEVERE, "Cannot load resource {0}: {1}", new Object[]{resource, err});
            return null;
        }
    }
}
