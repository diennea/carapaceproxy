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
package org.carapaceproxy;

import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import org.carapaceproxy.server.mapper.CustomHeader;

/**
 * Static response to sent to clients.
 *
 * @author paolo.venturi
 */
public record SimpleHTTPResponse(int errorCode, String resource, List<CustomHeader> customHeaders) {

    public SimpleHTTPResponse {
        customHeaders = List.copyOf(customHeaders);
    }

    public SimpleHTTPResponse(final int errorCode, final String resource) {
        this(errorCode, resource, List.of());
    }

    public static SimpleHTTPResponse notFound(final String resource) {
        return new SimpleHTTPResponse(HttpResponseStatus.NOT_FOUND.code(), resource);
    }

    public static SimpleHTTPResponse internalError(final String resource) {
        return new SimpleHTTPResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), resource);
    }

    public static SimpleHTTPResponse badRequest(final String resource) {
        return new SimpleHTTPResponse(HttpResponseStatus.BAD_REQUEST.code(), resource);
    }

    public static SimpleHTTPResponse serviceUnavailable(final String resource) {
        return new SimpleHTTPResponse(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), resource);
    }
}
