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
import java.io.UnsupportedEncodingException;
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
import org.carapaceproxy.server.HttpProxyServer;
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
    private static final String MESSAGE_INVALID_TOKEN_NOTSUPPORTED = "Invalid authentication token charset";
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
        if (tokenizer.hasMoreTokens()) {
            String basic = tokenizer.nextToken();
            if (basic.equalsIgnoreCase(HEADER_BASIC)) {
                try {
                    String authBase64 = tokenizer.nextToken().trim();
                    String credentials = new String(Base64.getDecoder().decode(authBase64), StandardCharsets.UTF_8);
                    int position = credentials.indexOf(":");
                    if (position < 0) {
                        UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN);
                        return;
                    }
                    String currentUser = null;
                    if (server != null) {
                        UserRealm userRealm = (UserRealm) server.getRealm();
                        String username = credentials.substring(0, position).trim();
                        String password = credentials.substring(position + 1).trim();

                        currentUser = userRealm.login(username, password);
                    }
                    if (currentUser == null) {
                        UNAUTHORIZED(response, MESSAGE_INVALID_CREDENTIALS);
                        return;
                    }

                    chain.doFilter(servletRequest, servletResponse);
                } catch (UnsupportedEncodingException e) {
                    UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN_NOTSUPPORTED);
                }
            }
        } else {
            UNAUTHORIZED(response, MESSAGE_INVALID_TOKEN);
        }
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
