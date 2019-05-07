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
package org.carapaceproxy.server.mapper.requestmatcher;

import io.netty.handler.codec.http.HttpRequest;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;

/**
 * Matcher by Regular Expression to requests URI
 *
 * @author paolo.venturi
 */
public class URIRequestMatcher implements RequestMatcher {

    private final Pattern expression;

    public URIRequestMatcher(String expression) throws ConfigurationNotValidException {
        try {
            this.expression = Pattern.compile(expression);
        } catch (PatternSyntaxException err) {
            throw new ConfigurationNotValidException(err);
        }
    }

    @Override
    public boolean matches(HttpRequest request) {
        return expression.matcher(request.uri()).matches();
    }

    @Override
    public String getDescription() {
        return "Matchig by RegEx: " + this.expression.toString();
    }

    @Override
    public String toString() {
        return "URIRequestMatcher{" + "regexp='" + expression + "'}";
    }

}
