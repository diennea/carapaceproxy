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
package org.carapaceproxy.client;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import org.carapaceproxy.server.RequestHandler;

/**
 * A Connection to a specific endpoint. Connections are pooled and so they have to be returned to the pool. A connection can be bound to at most one RequestHandler at a time.
 *
 * @author enrico.olivelli
 */
public interface EndpointConnection {

    public EndpointKey getKey();

    /**
     * Start a request and bind the connection to the RequestHandler.
     */
    public void sendRequest(HttpRequest request, RequestHandler handler);

    /**
     * Send other chunks (chunked payload from the client).
     *
     * @param httpContent
     * @param handler
     */
    public void sendChunk(HttpContent httpContent, RequestHandler handler);

    /**
     * Client finished its request.
     *
     * @param msg
     * @param handler
     */
    public void sendLastHttpContent(LastHttpContent msg, RequestHandler handler);

    /**
     * Connection is no more useful for the RequestHandler.
     *
     * @param forceClose
     * @param handler
     * @param onReleasePerformed
     */
    public void release(boolean forceClose, RequestHandler handler, Runnable onReleasePerformed);

}
