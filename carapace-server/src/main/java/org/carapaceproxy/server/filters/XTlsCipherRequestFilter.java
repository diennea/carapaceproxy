package org.carapaceproxy.server.filters;

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

public class XTlsCipherRequestFilter extends BasicRequestFilter{
    public static final String TYPE = "add-x-tls-cipher";

    public XTlsCipherRequestFilter(RequestMatcher matcher) {
        super(matcher);
    }

    @Override
    public void apply(ProxyRequest request) {
        if (!checkRequestMatching(request)) {
            return;
        }

        request.getRequestHeaders().remove("X-Tls-Cipher");
        request.getRequestHeaders().add("X-Tls-Cipher", request.getCipherSuite());
    }
}
