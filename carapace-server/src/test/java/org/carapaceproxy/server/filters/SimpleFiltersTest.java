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
import java.util.HashMap;
import java.util.Map;
import org.carapaceproxy.server.ClientConnectionHandler;
import org.carapaceproxy.server.RequestHandler;
import org.carapaceproxy.server.mapper.requestmatcher.MatchAllRequestMatcher;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SimpleFiltersTest {

    @Test
    public void testRegexpMapUserIdFilter() {

        Map<String, String> cases = new HashMap<>();
        cases.put("/test?param1=&sSID=H*29gnay4j68mt9c", "H");
        cases.put("/test?sSID=H*29gnay4j68mt9c", "H");
        cases.put("/test?param1=&ssid=H*29gnay4j68mt9c", "H"); // non case sensitive
        cases.put("/test?ssid=H*29gnay4j68mt9c", "H"); // non case sensitive
        cases.put("/test?param1=&sSID=", null);
        cases.put("/test?param1=&sSID=nomatch", null);

        cases.put("/test?param1=noparam", null);
        cases.put("/testnoquerystring", null);
        cases.put("/be/xxx/index.do?sSID=H*90s856faxe0tbq", "H");

        cases.forEach((uri, userId) -> {
            HttpRequest request = mock(HttpRequest.class);
            RequestHandler requestHandler = mock(RequestHandler.class);
            ClientConnectionHandler client = null;
            UrlEncodedQueryString queryString = RequestHandler.parseQueryString(uri);
            when(request.uri()).thenReturn(uri);
            when(requestHandler.getQueryString()).thenReturn(queryString);

            RegexpMapUserIdFilter instance = new RegexpMapUserIdFilter("sSID", "([\\w\\d]+)([*])", new MatchAllRequestMatcher());
            instance.apply(request, client, requestHandler);
            if (userId != null) {
                verify(requestHandler, times(1)).setUserId(eq(userId));
            } else {
                verify(requestHandler, times(0)).setUserId(ArgumentMatchers.anyString());
            }
        });

    }

    @Test
    public void testRegexpMapSessionIdFilter() {

        Map<String, String> cases = new HashMap<>();
        cases.put("/test?param1=&sSID=H*29gnay4j68mt9c", "H*29gnay4j68mt9c");
        cases.put("/test?sSID=H*29gnay4j68mt9c", "H*29gnay4j68mt9c");
        cases.put("/test?param1=&ssid=H*29gnay4j68mt9c", "H*29gnay4j68mt9c"); // non case sensitive
        cases.put("/test?ssid=H*29gnay4j68mt9c", "H*29gnay4j68mt9c"); // non case sensitive
        cases.put("/test?param1=&sSID=", null);
        cases.put("/test?param1=&sSID=nomatch", "nomatch");

        cases.put("/test?param1=noparam", null);
        cases.put("/testnoquerystring", null);
        cases.put("/be/xxx/index.do?sSID=H*90s856faxe0tbq", "H*90s856faxe0tbq");

        cases.forEach((uri, sessionId) -> {
            HttpRequest request = mock(HttpRequest.class);
            RequestHandler requestHandler = mock(RequestHandler.class);
            ClientConnectionHandler client = null;
            UrlEncodedQueryString queryString = RequestHandler.parseQueryString(uri);
            when(request.uri()).thenReturn(uri);
            when(requestHandler.getQueryString()).thenReturn(queryString);

            RegexpMapSessionIdFilter instance = new RegexpMapSessionIdFilter("sSID", "(.+)", new MatchAllRequestMatcher());
            instance.apply(request, client, requestHandler);
            if (sessionId != null) {
                verify(requestHandler, times(1)).setSessionId(eq(sessionId));
            } else {
                verify(requestHandler, times(0)).setSessionId(ArgumentMatchers.anyString());
            }
        });

    }

}
