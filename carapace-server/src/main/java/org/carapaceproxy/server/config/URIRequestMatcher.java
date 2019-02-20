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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class URIRequestMatcher implements RequestMatcher {

    private final Pattern expression;

    public URIRequestMatcher(String expression) {
        this.expression = Pattern.compile(expression);
    }

    @Override
    public RoutingKey matches(HttpRequest request) {
        Matcher matcher = expression.matcher(request.uri());
        if (matcher.matches()) {
            int groups = matcher.groupCount();
            Map<String, String> attributes = new HashMap<>();
            for (int i = 0; i <= groups; i++) {
                attributes.put(i + "", matcher.group(i));
            }
            return new AttributesRoutingKey(attributes);
        } else {
            return null;
        }
    }
    private static final Logger LOG = Logger.getLogger(URIRequestMatcher.class.getName());

    @Override
    public String appliedPattern() {
        return this.expression.toString();
    }

    @Override
    public String toString() {
        return "URIRequestMatcher{" + "regexp='" + expression + "'}";
    }

}
