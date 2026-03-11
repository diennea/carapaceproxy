package org.carapaceproxy.server.filters;

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

public class XTlsProtocolRequestFilter extends HeaderRequestFilter {

    public static final String TYPE = "add-x-tls-protocol";

    public XTlsProtocolRequestFilter(RequestMatcher matcher) {
        super("X-Tls-Protocol", matcher);
    }

    @Override
    protected String computeHeaderValue(ProxyRequest request) {
        if (!request.isSecure()) {
            return null;
        }
        return request.getSslProtocol();
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
