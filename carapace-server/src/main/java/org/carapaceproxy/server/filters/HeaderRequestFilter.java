package org.carapaceproxy.server.filters;

import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

/**
 * Base class for filters that simply compute and set a request header.
 */
public abstract class HeaderRequestFilter extends ConditionalRequestFilter {

    protected final String headerName;

    protected HeaderRequestFilter(String headerName, RequestMatcher matcher) {
        super(matcher);
        this.headerName = headerName;
    }

    /**
     * Compute the header value for the given request. May return null when no header should be set.
     */
    protected abstract String computeHeaderValue(ProxyRequest request);

    @Override
    public void applyFilter(ProxyRequest request) {
        String value = computeHeaderValue(request);
        if (value == null) {
            return;
        }
        setHeader(request, headerName, value);
    }

    protected final void setHeader(ProxyRequest request, String headerName, String value) {
        request.getRequestHeaders().remove(headerName);
        request.getRequestHeaders().add(headerName, value);
    }
}

