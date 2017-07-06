/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.io.FileNotFoundException;
import java.net.URL;
import nettyhttpproxy.HttpProxyServer;
import nettyhttpproxy.impl.SimpleEndpointMapper;
import org.apache.commons.io.IOUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;

/**
 *
 * @author enrico.olivelli
 */
public class SimpleHTTPProxyTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(18081);

    @Test
    public void test() throws Exception {

        stubFor(get(urlEqualTo("/index.html?redir"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("it <b>works</b> !!")));

        int port = 1234;
        SimpleEndpointMapper mapper = new SimpleEndpointMapper("localhost", wireMockRule.port());

        try (HttpProxyServer server = new HttpProxyServer("localhost", port, mapper);) {
            server.start();
            System.out.println("UP AND RUNNIGN ON PORT 1234!");
            System.out.println("http://" + server.getHost() + ":" + server.getPort() + "/index.html");

            // debug
            {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html").toURI(), "utf-8");
                System.out.println("s:" + s);
            }

            // not found
            try {
                String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?not-found").toURI(), "utf-8");
                System.out.println("s:" + s);
                fail();
            } catch (FileNotFoundException ok) {
            }

            // pipe
            String s = IOUtils.toString(new URL("http://localhost:" + port + "/index.html?redir").toURI(), "utf-8");
            System.out.println("s:" + s);
            assertEquals("it <b>works</b> !!", s);

        }
    }
}
