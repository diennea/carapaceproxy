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

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * Matcher for composing OR expressions with other matchers.
 * 
 * @author paolo.venturi
 */
public class OrRequestMatcher implements RequestMatcher {

    private final List<RequestMatcher> matchers;
    private final boolean wrap;

    public OrRequestMatcher(List<RequestMatcher> matchers, boolean wrap) {
        this.matchers = matchers;
        this.wrap = wrap;
    }

    @Override
    public boolean matches(MatchingContext context) {
        for (RequestMatcher matcher : matchers) {            
            if (matcher.matches(context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getDescription() {
        String desc = wrap ? "(" : "";
        desc += matchers.stream()
                    .map(RequestMatcher::getDescription)
                    .collect(Collectors.joining(" or "));
        desc += wrap ? ")" : "";
        
        return desc;
    }

}
