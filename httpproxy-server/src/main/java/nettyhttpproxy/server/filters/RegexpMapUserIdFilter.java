/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package nettyhttpproxy.server.filters;

import io.netty.handler.codec.http.HttpRequest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nettyhttpproxy.server.ClientConnectionHandler;
import nettyhttpproxy.server.RequestFilter;
import nettyhttpproxy.server.RequestHandler;

/**
 * Maps a parameter of the querystring to the tenant, using simple pattern
 * matching
 *
 * @author enrico.olivelli
 */
public class RegexpMapUserIdFilter implements RequestFilter {

    private static final Logger LOG = Logger.getLogger(RegexpMapUserIdFilter.class.getName());

    private final String parameterName;
    private final Pattern compiledPattern;

    public RegexpMapUserIdFilter(String parameterName, String pattern) {
        this.parameterName = parameterName;
        this.compiledPattern = Pattern.compile(pattern);
    }

    @Override
    public void apply(HttpRequest request, ClientConnectionHandler client, RequestHandler requestHandler) {
        String uri = request.uri();
        int pos = uri.indexOf('?');
        if (pos < 0 || pos == uri.length() - 1) {
            return;
        }
        UrlEncodedQueryString parsed = UrlEncodedQueryString.parse(uri.substring(pos + 1));
        String value = parsed.get(parameterName);
        if (value == null) {
            return;
        }
        Matcher matcher = compiledPattern.matcher(value);
        if (!matcher.find()) {
            return;
        }
        if (matcher.groupCount() <= 0) {
            return;
        }
        String group = matcher.group(1);
        requestHandler.setUserId(group);
    }

}
