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
package org.carapaceproxy.api;

import java.io.IOException;
import java.util.Set;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.core.HttpProxyServer;

/** Refuse admin writes with 503 while a restore is in progress on this node; reads, backup and restore pass. */
public class RestoreInProgressFilter implements Filter {

    /** Seconds a client should wait before retrying: a restore usually takes a few seconds. */
    public static final int RETRY_AFTER_SECONDS = 10;

    /** The body of the refusal, shaped as the other API answers. */
    private static final String REFUSAL_BODY = "{\"message\":\"Restore in progress, try again later\"}";

    private static final Set<String> ALLOWED_PATHS = Set.of("/api/config/backup", "/api/config/restore");

    private HttpProxyServer server;

    @Override
    public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse, final FilterChain chain)
            throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) servletRequest;
        if (server != null
                && server.isRestoreInProgress()
                && !"GET".equals(request.getMethod())
                && !ALLOWED_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            final HttpServletResponse response = (HttpServletResponse) servletResponse;
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
            // not sendError: Jetty would answer with its HTML error page
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write(REFUSAL_BODY);
            return;
        }
        chain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.server = (HttpProxyServer) filterConfig.getServletContext().getAttribute("server");
    }

    @Override
    public void destroy() {
    }

}
