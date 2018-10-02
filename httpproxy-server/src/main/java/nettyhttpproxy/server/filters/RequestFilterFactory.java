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

import java.util.Map;
import nettyhttpproxy.server.RequestFilter;
import nettyhttpproxy.server.config.ConfigurationNotValidException;
import nettyhttpproxy.server.config.RequestFilterConfiguration;

/**
 * Factory for all filters.
 *
 * @author enrico.olivelli
 */
public class RequestFilterFactory {

    public static RequestFilter buildRequestFilter(RequestFilterConfiguration config) throws ConfigurationNotValidException {
        String type = config.getType();
        Map<String, String> filterConfig = config.getFilterConfig();
        switch (type) {
            case XForwardedForRequestFilter.TYPE:
                return new XForwardedForRequestFilter();
            case RegexpMapUserIdFilter.TYPE: {
                String param = filterConfig.getOrDefault("param", "userid").trim();
                String regexp = filterConfig.getOrDefault("regexp", "(.*)").trim();
                return new RegexpMapUserIdFilter(param, regexp);
            }
            case RegexpMapSessionIdFilter.TYPE: {
                String param = filterConfig.getOrDefault("param", "sid").trim();
                String regexp = filterConfig.getOrDefault("regexp", "(.*)").trim();
                return new RegexpMapSessionIdFilter(param, regexp);
            }
            default:
                throw new ConfigurationNotValidException("bad filter type '" + type
                        + "' only 'add-x-forwarded-for', 'match-user-regexp'");
        }
    }

}
