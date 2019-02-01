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

import java.util.List;
import java.util.Properties;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.utils.RawHttpClient;
import org.carapaceproxy.utils.RawHttpClient.BasicAuthCredentials;
import org.carapaceproxy.utils.RawHttpClient.HttpResponse;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author enrico.olivelli
 */
public class AuthenticationAPIServerTest extends UseAdminServer {

    @Test
    public void testUnauthorizedRequests() throws Exception {
        Properties prop = new Properties();

        prop.put("userrealm.class", "org.carapaceproxy.utils.TestUserRealm");
        prop.put("user.test1", "pass1");
        prop.put("user.test2", "pass2");

        startServer(prop);

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            BasicAuthCredentials credentials = new BasicAuthCredentials("test1", "pass1"); // valid credentials
            HttpResponse resp = client.get("/api/up", credentials);
            assertHeaderNotContains(resp, "WWW-Authenticate");
        }

        try (RawHttpClient client = new RawHttpClient("localhost", 8761)) {
            BasicAuthCredentials credentials = new BasicAuthCredentials("wrongtest1", "wrongtest1"); // not valid credentials
            HttpResponse resp = client.get("/api/up", credentials);
            assertHeaderContains(resp, "WWW-Authenticate");
            assertThat(resp.getBodyString(), containsString(HttpServletResponse.SC_UNAUTHORIZED + ""));
        }
    }

    private void assertHeaderNotContains(HttpResponse resp, String header) {
        List<String> lines = resp.getHeaderLines();
        assertFalse(lines.stream().anyMatch(h -> h.contains(header)));
    }

    private void assertHeaderContains(HttpResponse resp, String header) {
        List<String> lines = resp.getHeaderLines();
        assertTrue(lines.stream().anyMatch(h -> h.contains(header)));
    }

}
