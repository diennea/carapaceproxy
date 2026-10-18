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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.cluster.GroupMembershipHandler;
import org.carapaceproxy.cluster.impl.NullGroupMembershipHandler;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.junit.Test;

public class RestoreInProgressFilterTest {

    @Test
    public void testWritesRefusedWhileRestoreRuns() throws Exception {
        NullGroupMembershipHandler handler = new NullGroupMembershipHandler();
        HttpProxyServer server = mock(HttpProxyServer.class);
        when(server.isRestoreInProgress()).thenAnswer(invocation -> handler.isRestoreInProgress());
        ServletContext context = mock(ServletContext.class);
        when(context.getAttribute("server")).thenReturn(server);
        FilterConfig config = mock(FilterConfig.class);
        when(config.getServletContext()).thenReturn(context);
        RestoreInProgressFilter filter = new RestoreInProgressFilter();
        filter.init(config);

        // no restore: writes pass
        assertPasses(filter, "POST", "/api/config/apply");

        GroupMembershipHandler.RestoreSignal signal = handler.raiseRestoreSignal();
        try {
            signal.ensureHeld();
            assertThrows(ConfigurationChangeInProgressException.class, handler::raiseRestoreSignal);
            assertRefused(filter, "POST", "/api/config/apply");
            assertRefused(filter, "DELETE", "/api/certificates/a.example");
            assertRefused(filter, "PUT", "/api/connectionpools/p");
            assertPasses(filter, "GET", "/api/config");
            assertPasses(filter, "GET", "/api/config/backup");
            assertPasses(filter, "POST", "/api/config/restore");
        } finally {
            signal.close();
        }
        assertThrows(IllegalStateException.class, signal::ensureHeld);
        assertPasses(filter, "POST", "/api/config/apply");

        // a stale handle never clears a newer signal
        try (GroupMembershipHandler.RestoreSignal second = handler.raiseRestoreSignal()) {
            signal.close();
            second.ensureHeld();
            assertRefused(filter, "POST", "/api/config/apply");
        }
        assertPasses(filter, "POST", "/api/config/apply");
    }

    private static void assertRefused(RestoreInProgressFilter filter, String method, String uri) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest request = request(method, uri);
        filter.doFilter(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response).setHeader("Retry-After", String.valueOf(RestoreInProgressFilter.RETRY_AFTER_SECONDS));
        verify(response).setContentType("application/json");
        assertEquals("{\"message\":\"Restore in progress, try again later\"}", body.toString());
    }

    private static void assertPasses(RestoreInProgressFilter filter, String method, String uri) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest request = request(method, uri);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContextPath()).thenReturn("");
        return request;
    }
}
