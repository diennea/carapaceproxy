package org.carapaceproxy.server.filters;

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

public class XTlsProtocolRequestFilter extends BasicRequestFilter {

    public static final String TYPE = "add-x-tls-protocol";

    public XTlsProtocolRequestFilter(RequestMatcher matcher) {
        super(matcher);
    }

    @Override
    public void apply(ProxyRequest request) {
        if (!checkRequestMatching(request)) {
            return;
        }

        request.getRequestHeaders().remove("X-Tls-Protocol");
        request.getRequestHeaders().add("X-Tls-Protocol", request.getSslProtocol());
    }
}
