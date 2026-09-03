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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * @author enrico.olivelli
 */
class AuthenticationAPIServerIT extends UseAdminServer {

    @Test
    void unauthorizedRequests() throws Exception {
        Properties prop = new Properties(HTTP_ADMIN_SERVER_CONFIG);

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
            assertThat(resp.getBodyString()).contains(HttpServletResponse.SC_UNAUTHORIZED + "");
        }
    }

    private void assertHeaderNotContains(HttpResponse resp, String header) {
        List<String> lines = resp.getHeaderLines();
        assertThat(lines).noneSatisfy(h -> assertThat(h).contains(header));
    }

    private void assertHeaderContains(HttpResponse resp, String header) {
        List<String> lines = resp.getHeaderLines();
        assertThat(lines).anySatisfy(h -> assertThat(h).contains(header));
    }

}
