package org.carapaceproxy.server.filters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.carapaceproxy.core.ProxyRequest;
import org.carapaceproxy.server.mapper.requestmatcher.RequestMatcher;

/**
 * Base class for filters that map a query-string parameter via regex to some request property.
 */
public abstract class RegexMapRequestFilter extends ConditionalRequestFilter {

    protected final String parameterName;
    protected final Pattern compiledPattern;

    protected RegexMapRequestFilter(String parameterName, String pattern, RequestMatcher matcher) {
        super(matcher);
        this.parameterName = parameterName;
        this.compiledPattern = Pattern.compile(pattern);
    }

    public String getParameterName() {
        return parameterName;
    }

    public Pattern getCompiledPattern() {
        return compiledPattern;
    }

    /**
     * Apply the mapped value to the request when the pattern matches the parameter. Implementations
     * receive the first capturing group (or null if none/missing) and should apply it appropriately.
     */
    protected abstract void applyMappedValue(ProxyRequest request, String group);

    @Override
    public void applyFilter(ProxyRequest request) {
        String group = extractFirstRegexGroupFromQuery(request, parameterName, compiledPattern);
        if (group == null) {
            return;
        }
        applyMappedValue(request, group);
    }

    protected final String extractFirstRegexGroupFromQuery(ProxyRequest request, String parameterName, Pattern pattern) {
        UrlEncodedQueryString queryString = request.getQueryString();
        if (queryString == null) {
            return null;
        }
        String value = queryString.get(parameterName);
        if (value == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        if (matcher.groupCount() <= 0) {
            return null;
        }
        return matcher.group(1);
    }
}
