package org.carapaceproxy.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpMethod;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.api.UseAdminServer;
import org.junit.jupiter.api.Test;

class JettyHttpTraceMethodIT extends UseAdminServer {

    @Test
    void httpTraceMethodTest() throws Exception {
        startAdmin();
        String URL = "http://localhost:8761";
        URL url = URI.create(URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(String.valueOf(HttpMethod.TRACE));
        int code = conn.getResponseCode();
        assertThat(code).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }
}
