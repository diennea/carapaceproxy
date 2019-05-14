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
package org.carapaceproxy.server.filters;

import io.netty.handler.codec.http.HttpRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.carapaceproxy.server.ClientConnectionHandler;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

/**
 * Maps a parameter of the querystring to the sessionId, using simple pattern matching
 *
 * @author enrico.olivelli
 */
public class RegexpMapSessionIdFilter extends BasicRequestFilter {

    public static final String TYPE = "match-session-regexp";

    private final String parameterName;
    private final Pattern compiledPattern;

    public RegexpMapSessionIdFilter(String parameterName, String pattern, RequestMatcher matcher) {
        super(matcher);
        this.parameterName = parameterName;
        this.compiledPattern = Pattern.compile(pattern);
    }

    public String getParameterName() {
        return parameterName;
    }

    public Pattern getCompiledPattern() {
        return compiledPattern;
    }

    @Override
    public void apply(HttpRequest request, ClientConnectionHandler client, RequestHandler requestHandler) {
        if (!checkRequestMatching(requestHandler)) {
            return;
        }
        UrlEncodedQueryString queryString = requestHandler.getQueryString();
        String value = queryString.get(parameterName);
        if (value == null) {
            return;
        }
        Matcher _matcher = compiledPattern.matcher(value);
        if (!_matcher.find()) {
            return;
        }
        if (_matcher.groupCount() <= 0) {
            return;
        }
        String group = _matcher.group(1);
        requestHandler.setSessionId(group);
    }
}
