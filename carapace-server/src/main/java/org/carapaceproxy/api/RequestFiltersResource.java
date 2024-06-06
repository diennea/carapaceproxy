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
package org.carapaceproxy.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RequestFilter;
import org.carapaceproxy.server.filters.RegexpMapSessionIdFilter;
import org.carapaceproxy.server.filters.RegexpMapUserIdFilter;
import org.carapaceproxy.server.filters.XForwardedForRequestFilter;
import org.carapaceproxy.server.filters.XTlsCipherRequestFilter;
import org.carapaceproxy.server.filters.XTlsProtocolRequestFilter;

/**
 * Access to request filters
 *
 * @author matteo.minardi
 */
@Path("/requestfilters")
@Produces("application/json")
public class RequestFiltersResource {

    @Context
    private ServletContext context;

    public static final class RequestFilterBean {

        private String type;
        private final Map<String, Object> values = new HashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Object> getValues() {
            return values;
        }

        public void addValue(String key, Object value) {
            this.values.put(key, value);
        }

    }

    @GET
    @Path("/")
    @SuppressWarnings("deprecation")
    public List<RequestFilterBean> getAllRequestFilters() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");

        List<RequestFilterBean> res = new ArrayList<>();
        for (RequestFilter f : server.getFilters()) {
            RequestFilterBean filterBean = new RequestFilterBean();
            if (f instanceof XForwardedForRequestFilter) {
                filterBean.setType(XForwardedForRequestFilter.TYPE);
                res.add(filterBean);
            } else if (f instanceof XTlsCipherRequestFilter) {
                filterBean.setType(XTlsCipherRequestFilter.TYPE);
                res.add(filterBean);
            } else if (f instanceof XTlsProtocolRequestFilter) {
                filterBean.setType(XTlsProtocolRequestFilter.TYPE);
                res.add(filterBean);
            } else if (f instanceof final RegexpMapUserIdFilter filter) {
                filterBean.setType(RegexpMapUserIdFilter.TYPE);
                filterBean.addValue("parameterName", filter.getParameterName());
                filterBean.addValue("compiledPattern", filter.getCompiledPattern());
                res.add(filterBean);
            } else if (f instanceof final RegexpMapSessionIdFilter filter) {
                filterBean.setType(RegexpMapSessionIdFilter.TYPE);
                filterBean.addValue("parameterName", filter.getParameterName());
                filterBean.addValue("compiledPattern", filter.getCompiledPattern());
                res.add(filterBean);
            }
        }
        return res;
    }

}
