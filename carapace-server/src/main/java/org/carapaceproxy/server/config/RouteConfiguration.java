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

import io.netty.handler.codec.http.HttpRequest;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.carapaceproxy.server.mapper.requestmatcher.ParseException;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

/**
 * Route
 */
public class RouteConfiguration {

    private final String id;
    private final String action;
    private final boolean enabled;
    private final String matchingCondition;

    public RouteConfiguration(String id, String action, boolean enabled, String matchingCondition) {
        this.id = id;
        this.action = action;
        this.enabled = enabled;
        this.matchingCondition = matchingCondition;
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMatchingCondition() {
        return matchingCondition;
    }

    public boolean matches(HttpRequest request) {
        if (enabled) {
            try {
                return RequestMatcher.matches(request, matchingCondition);
            } catch (ParseException | IOException ex) {
                Logger.getLogger(RouteConfiguration.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return false;
    }

}
