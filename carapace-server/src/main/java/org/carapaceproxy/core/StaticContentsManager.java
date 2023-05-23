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
import io.netty.handler.codec.http.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;

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
    public static final String DEFAULT_MAINTENANCE_MODE_ERROR =  CLASSPATH_RESOURCE + "/default-error-pages/500_maintenance.html";
    public static final String DEFAULT_BAD_REQUEST =  CLASSPATH_RESOURCE + "/default-error-pages/400_badrequest.html";
    public static final String DEFAULT_SERVICE_UNAVAILABLE_ERROR =  CLASSPATH_RESOURCE + "/default-error-pages/503_serviceunavailable.html";

    private static final Logger LOG = Logger.getLogger(StaticContentsManager.class.getName());

    private final ConcurrentHashMap<String, ByteBuf> contents = new ConcurrentHashMap<>();

    public void close() {
        contents.values().forEach(ByteBuf::release);
        contents.clear();
    }

    /**
     * Build an HTTP response for the provided code and resource.
     *
     * @param code        the HTTP code;
     *                    it results to {@value HttpStatus#SC_INTERNAL_SERVER_ERROR} if no code is provided,
     *                    or {@value HttpStatus#SC_NOT_FOUND} if no content is loadable for the resource
     * @param resource    the resource that identify the content to serve
     * @param httpVersion the HTTP version to use
     * @return the built response
     */
    public DefaultFullHttpResponse buildResponse(final int code, final String resource, final HttpVersion httpVersion) {
        final var content = resource != null
                ? contents.computeIfAbsent(resource, StaticContentsManager::loadResource)
                : null;
        final DefaultFullHttpResponse res;
        final HttpResponseStatus httpStatus;
        if (content != null) {
            final var retainedContent = content.retainedDuplicate();
            httpStatus = HttpResponseStatus.valueOf(code <= 0 ? HttpStatus.SC_INTERNAL_SERVER_ERROR : code);
            res = new DefaultFullHttpResponse(httpVersion, httpStatus, retainedContent);
            res.headers().set(HttpHeaderNames.CONTENT_LENGTH, retainedContent.readableBytes());
        } else {
            httpStatus = HttpResponseStatus.valueOf(HttpStatus.SC_NOT_FOUND);
            res = new DefaultFullHttpResponse(httpVersion, httpStatus);
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
                return Unpooled.wrappedBuffer(resource.getBytes(StandardCharsets.UTF_8));
            } else {
                throw new IOException("cannot load resource " + resource + ", path must start with " + FILE_RESOURCE + " or " + CLASSPATH_RESOURCE);
            }
        } catch (IOException | NullPointerException err) {
            LOG.log(Level.SEVERE, "Cannot load resource {0}: {1}", new Object[]{resource, err});
            return null;
        }
    }
}
