package org.carapaceproxy.server.filters;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.HashMap;
import java.util.Map;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.core.RequestFilter;
import org.carapaceproxy.server.config.RequestFilterConfiguration;
import org.junit.Test;

public class ServerHeaderRequestFilterTest {

    @Test
    public void testFactoryFallbackUsesDefaultWhenValueMissing() throws Exception {
        Map<String, String> cfg = new HashMap<>(); // no 'value' key
        RequestFilterConfiguration rfc = new RequestFilterConfiguration(ServerHeaderRequestFilter.TYPE, cfg);
        RequestFilter filter = RequestFilterFactory.buildRequestFilter(rfc);
        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        when(request.getRequestHeaders()).thenReturn(headers);

        filter.apply(request);

        assertThat(headers.get(HttpHeaderNames.SERVER), is(ServerHeaderRequestFilter.DEFAULT_SERVER));
    }

    @Test
    public void testFactoryUsesProvidedValueToOverride() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("value", "MyServer/1.0");
        RequestFilterConfiguration rfc = new RequestFilterConfiguration(ServerHeaderRequestFilter.TYPE, cfg);
        RequestFilter filter = RequestFilterFactory.buildRequestFilter(rfc);

        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        when(request.getRequestHeaders()).thenReturn(headers);

        filter.apply(request);

        assertThat(headers.get(HttpHeaderNames.SERVER), is("MyServer/1.0"));
    }

    @Test
    public void testFactoryEmptyValueRemovesHeader() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("value", "");
        RequestFilterConfiguration rfc = new RequestFilterConfiguration(ServerHeaderRequestFilter.TYPE, cfg);
        RequestFilter filter = RequestFilterFactory.buildRequestFilter(rfc);

        ProxyRequest request = mock(ProxyRequest.class);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.SERVER, "SomeValue");
        when(request.getRequestHeaders()).thenReturn(headers);

        filter.apply(request);

        assertTrue(headers.getAll(HttpHeaderNames.SERVER).isEmpty());
    }
}

