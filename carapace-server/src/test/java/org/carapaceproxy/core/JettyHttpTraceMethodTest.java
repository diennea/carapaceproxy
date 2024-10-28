package org.carapaceproxy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.handler.codec.http.HttpMethod;
import org.carapaceproxy.api.UseAdminServer;
import org.junit.jupiter.api.Test;
import javax.servlet.http.HttpServletResponse;
import java.net.HttpURLConnection;
import java.net.URL;

public class JettyHttpTraceMethodTest extends UseAdminServer {

    @Test
    public void httpTraceMethodTest() throws Exception {
        startAdmin();
        String URL = "http://localhost:8761";
        URL url = new URL(URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(String.valueOf(HttpMethod.TRACE));
        int code = conn.getResponseCode();
        assertEquals(HttpServletResponse.SC_FORBIDDEN, code);
    }
}
