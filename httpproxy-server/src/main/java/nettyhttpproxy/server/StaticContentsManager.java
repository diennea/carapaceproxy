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
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * Handles error pages loading
 */
public class StaticContentsManager {

    public static final String DEFAULT_NOT_FOUND = "classpath:/default-error-pages/404_notfound.html";
    public static final String DEFAULT_INTERNAL_SERVER_ERROR = "classpath:/default-error-pages/500_internalservererror.html";

    private final ConcurrentHashMap<String, ByteBuf> contents = new ConcurrentHashMap<>();

    public DefaultFullHttpResponse buildResponse(int code, String resource) {
        if (code <= 0) {
            code = 500;
        }
        ByteBuf content;
        if (resource != null) {
            content = contents.computeIfAbsent(resource, StaticContentsManager::loadResource);
            content = content.retainedDuplicate();
        } else {
            content = null;
        }
        DefaultFullHttpResponse res;
        if (content != null) {
            int contentType = content.readableBytes();
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code), content);
            res.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentType);
        } else {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code));
        }
        res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        return res;
    }

    public void close() {
        contents.clear();
    }

    private static ByteBuf loadResource(String resource) {
        try {
            if (resource.startsWith("classpath:")) {
                resource = resource.substring("classpath:".length());
                try (InputStream resourceAsStream = StaticContentsManager.class.getResourceAsStream(resource);) {
                    if (resourceAsStream == null) {
                        throw new FileNotFoundException("no such resource " + resource + " on classpath");
                    }
                    return Unpooled.wrappedBuffer(IOUtils.toByteArray(resourceAsStream));
                }
            } else if (resource.startsWith("file:")) {
                resource = resource.substring("file:".length());
                return Unpooled.wrappedBuffer(FileUtils.readFileToByteArray(new File(resource)));
            } else {
                throw new IOException("cannot load resource " + resource + ", path must start with file: or classpath:");
            }
        } catch (IOException | NullPointerException err) {
            LOG.severe("Cannot load resource " + resource + ": " + err);
            return null;
        }
    }
    private static final Logger LOG = Logger.getLogger(StaticContentsManager.class.getName());
}
