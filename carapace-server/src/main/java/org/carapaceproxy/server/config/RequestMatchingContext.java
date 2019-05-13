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
package org.carapaceproxy.server.config;

import javax.ws.rs.core.HttpHeaders;
import org.carapaceproxy.server.ClientConnectionHandler;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.mapper.requestmatcher.MatchingContext;
import org.carapaceproxy.server.mapper.requestmatcher.MatchingException;

/**
 *
 * @author paolo.venturi
 */
public class RequestMatchingContext implements MatchingContext {

    // All properties name have been converted to lowercase during parsing.
    public static final String PROPERTY_HTTPS = "https";
    public static final String PROPERTY_URI = "request.uri";
    public static final String PROPERTY_METHOD = "request.method";
    public static final String PROPERTY_CONTENT_TYPE = "request.content-type";
    public static final String PROPERTY_HEADERS = "request.headers.";
    public static final String PROPERTY_LISTENER_ADDRESS = "listener.address";    
    private final RequestHandler handler;

    public RequestMatchingContext(RequestHandler handler) {
        this.handler = handler;
    }

    @Override
    public Object getProperty(String name) throws MatchingException {
        if (name.startsWith(PROPERTY_HEADERS)) {            
            return headerValueOrDefault(name.replaceFirst(PROPERTY_HEADERS, ""), "");
        } else {
            switch (name) {
                case PROPERTY_HTTPS:
                    return handler.getClientConnectionHandler().isSecure();
                case PROPERTY_URI:
                    return handler.getUri();
                case PROPERTY_METHOD:
                    return handler.getRequest().method().name();
                case PROPERTY_CONTENT_TYPE:
                    return headerValueOrDefault(HttpHeaders.CONTENT_TYPE, "");
                case PROPERTY_LISTENER_ADDRESS: {
                    ClientConnectionHandler cch = handler.getClientConnectionHandler();
                    return cch.getListenerHost() + ":" + cch.getListenerPort();
                }
                default:
                    throw new MatchingException("Property name " + name + " does not exists.");
            }
        }
    }

    private String headerValueOrDefault(String name, String defaultValue) {
        String value = handler.getRequest().headers().get(name);
        return value == null ? defaultValue : value;
    }

}
