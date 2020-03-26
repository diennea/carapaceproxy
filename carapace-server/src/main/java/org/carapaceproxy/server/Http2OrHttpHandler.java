/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.carapaceproxy.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import java.util.function.Consumer;

/**
 * Used during protocol negotiation, the main function of this handler is to
 * return the HTTP/1.1 or HTTP/2 handler once the protocol has been negotiated.
 */
public class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {

    private final Consumer<ChannelHandlerContext> onHttp2Callback;
    private final Consumer<ChannelHandlerContext> onHttpCallback;

    public Http2OrHttpHandler(Consumer<ChannelHandlerContext> onHttp2Callback, Consumer<ChannelHandlerContext> onHttpCallback) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.onHttp2Callback = onHttp2Callback;
        this.onHttpCallback = onHttpCallback;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            this.onHttp2Callback.accept(ctx);
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            this.onHttpCallback.accept(ctx);
            return;
        }

        throw new IllegalStateException("unknown protocol: " + protocol);
    }
}
