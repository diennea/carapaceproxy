package org.carapaceproxy.server.filters;

import org.apache.http.HttpHeaders;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;
import org.carapaceproxy.utils.StringUtils;

/**
 * Filter that adds a Server header with a fixed value.
 */
public class ServerHeaderRequestFilter extends HeaderRequestFilter {

    public static final String TYPE = "add-server-header";
    public static final String DEFAULT_SERVER = "CarapaceProxy";

    private final String headerValue;

    public ServerHeaderRequestFilter(String headerValue, RequestMatcher matcher) {
        super(HttpHeaders.SERVER, matcher);
        this.headerValue = headerValue;
    }

    @Override
    protected String computeHeaderValue(ProxyRequest request) {
        return headerValue;
    }

    @Override
    public void applyFilter(ProxyRequest request) {
        if (StringUtils.isBlank(headerValue)) {
            request.getRequestHeaders().remove(headerName);
            return;
        }
        super.applyFilter(request);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
