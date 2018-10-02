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
package nettyhttpproxy.server.filters;

import io.netty.handler.codec.http.HttpRequest;
import java.net.InetSocketAddress;
import nettyhttpproxy.server.ClientConnectionHandler;
import nettyhttpproxy.server.RequestFilter;
import nettyhttpproxy.server.RequestHandler;

/**
 * Add a X-Forwarded-For Header
 */
public class XForwardedForRequestFilter implements RequestFilter {

    public static final String TYPE = "add-x-forwarded-for";

    @Override
    public void apply(HttpRequest request, ClientConnectionHandler client, RequestHandler requestHandler) {
        request.headers().remove("X-Forwarded-For");
        if (client.getClientAddress() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) client.getClientAddress();
            request.headers().add("X-Forwarded-For", address.getAddress().getHostAddress());
        }
    }

}
