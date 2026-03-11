package org.carapaceproxy.server.filters;

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

public class XTlsCipherRequestFilter extends HeaderRequestFilter {
    public static final String TYPE = "add-x-tls-cipher";

    public XTlsCipherRequestFilter(RequestMatcher matcher) {
        super("X-Tls-Cipher", matcher);
    }

    @Override
    protected String computeHeaderValue(ProxyRequest request) {
        if (!request.isSecure()) {
            return null;
        }
        return request.getCipherSuite();
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
