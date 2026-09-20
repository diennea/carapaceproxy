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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringTokenizer;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.user.UserRealm;

/**
 * Basic HTTP Authorization filter
 *
 * @author matteo.minardi
 */
public class AuthAPIRequestsFilter implements Filter {

    private static final String HEADER_AUTH = "Authorization";
    private static final String HEADER_BASIC = "Basic";

    private static final String MESSAGE_INVALID_CREDENTIALS = "Bad credentials";
    private static final String MESSAGE_INVALID_TOKEN = "Invalid authentication token";

    private HttpProxyServer server;

    @Override
    public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse, final FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String authorizationHeader = request.getHeader(HEADER_AUTH);
        if (authorizationHeader == null) {
            UNAUTHORIZED(response);
            return;
        }

        StringTokenizer tokenizer = new StringTokenizer(authorizationHeader);
        if (tokenizer.countTokens() != 2 || !tokenizer.nextToken().equalsIgnoreCase(HEADER_BASIC)) {
            UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN);
            return;
        }

        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(tokenizer.nextToken().trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN);
            return;
        }
        int position = credentials.indexOf(":");
        if (position < 0) {
            UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN);
            return;
        }

        UserRealm userRealm = server == null ? null : server.getRealm();
        if (userRealm == null) {
            UNAUTHORIZED(response, MESSAGE_INVALID_CREDENTIALS);
            return;
        }
        String currentUser = userRealm.login(
                credentials.substring(0, position).trim(),
                credentials.substring(position + 1).trim());
        if (currentUser == null) {
            UNAUTHORIZED(response, MESSAGE_INVALID_CREDENTIALS);
            return;
        }

        chain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void init(FilterConfig fc) throws ServletException {
        ServletContext context = fc.getServletContext();
        this.server = (HttpProxyServer) context.getAttribute("server");
    }

    @Override
    public void destroy() {
    }

    private void UNAUTHORIZED(HttpServletResponse response, String message) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"ProxyAPI\" charset=\"" + StandardCharsets.UTF_8.name() + "\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    private void UNAUTHORIZED(HttpServletResponse response) throws IOException {
        UNAUTHORIZED(response, "Unauthorized");
    }

}
