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

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.RequestFilter;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

/**
 *
 * Root class for all RequestFilters
 *
 * @author paolo.venturi
 */
public abstract class ConditionalRequestFilter implements RequestFilter {

    private final RequestMatcher matcher;

    public ConditionalRequestFilter(RequestMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * Check if the request matches the filter condition.
     *
     * <br>This method is called before applying the filter to the request.
     *
     * @param request the request to check for matching the filter condition
     * @return true if the request matches the filter condition, false otherwise
     */
    protected boolean checkRequestMatching(ProxyRequest request) {
        return matcher.matches(request);
    }

    @Override
    public final void apply(ProxyRequest request) {
        if (checkRequestMatching(request)) {
            applyFilter(request);
        }
    }

    /**
     * Apply the filter to the request.
     *
     * <br>This method is called only if the request matches the filter condition.
     *
     * @param request the request to apply the filter to, if matching the condition
     */
    protected abstract void applyFilter(final ProxyRequest request);
}
