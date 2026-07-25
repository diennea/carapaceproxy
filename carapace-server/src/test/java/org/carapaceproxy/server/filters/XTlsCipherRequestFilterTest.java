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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.MatchAllRequestMatcher;
import org.junit.jupiter.api.Test;

class XTlsCipherRequestFilterTest {

    private static final String HEADER = "X-Tls-Cipher";

    @Test
    void plaintextRequestGetsNoCipherHeader() {
        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        when(request.getRequestHeaders()).thenReturn(headers);
        when(request.isSecure()).thenReturn(false);

        new XTlsCipherRequestFilter(new MatchAllRequestMatcher()).apply(request);

        assertTrue(headers.getAll(HEADER).isEmpty());
    }

    @Test
    void secureRequestGetsCipherHeader() {
        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        when(request.getRequestHeaders()).thenReturn(headers);
        when(request.isSecure()).thenReturn(true);
        when(request.getCipherSuite()).thenReturn("TLS_AES_128_GCM_SHA256");

        new XTlsCipherRequestFilter(new MatchAllRequestMatcher()).apply(request);

        assertEquals("TLS_AES_128_GCM_SHA256", headers.get(HEADER));
    }

    @Test
    void secureRequestOverridesIncomingCipherHeader() {
        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HEADER, "spoofed");
        when(request.getRequestHeaders()).thenReturn(headers);
        when(request.isSecure()).thenReturn(true);
        when(request.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");

        new XTlsCipherRequestFilter(new MatchAllRequestMatcher()).apply(request);

        assertEquals(List.of("TLS_AES_256_GCM_SHA384"), headers.getAll(HEADER));
    }
}
