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

import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;
import org.carapaceproxy.server.RequestHandler;

/**
 * Route
 */
public class RouteConfiguration {

    private final String id;
    private final boolean enabled;
    private final RequestMatcher matcher;
    private final String action;
    private String errorAction;

    public RouteConfiguration(String id, String action, boolean enabled, RequestMatcher matcher) {
        this.id = id;
        this.action = action;
        this.enabled = enabled;
        this.matcher = matcher;
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getErrorAction() {
        return errorAction;
    }

    public void setErrorAction(String errorAction) {
        this.errorAction = errorAction;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RequestMatcher getMatcher() {
        return matcher;
    }

    public boolean matches(RequestHandler handler) {
        if (!enabled) {
            return false;
        }
        return matcher.matches(handler);
    }

}
