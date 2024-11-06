package org.carapaceproxy.core;

import static org.junit.Assert.assertEquals;
import io.netty.handler.codec.http.HttpMethod;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.servlet.http.HttpServletResponse;
import org.carapaceproxy.api.UseAdminServer;
import org.junit.Test;

public class JettyHttpTraceMethodTest extends UseAdminServer {

    @Test
    public void httpTraceMethodTest() throws Exception {
        startAdmin();
        String URL = "http://localhost:8761";
        URL url = URI.create(URL).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(String.valueOf(HttpMethod.TRACE));
        int code = conn.getResponseCode();
        assertEquals(HttpServletResponse.SC_FORBIDDEN, code);
    }
}
