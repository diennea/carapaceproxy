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
package org.carapaceproxy.server.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import org.carapaceproxy.server.mapper.CustomHeader;

/**
 * Action
 */
@Data
public class ActionConfiguration {

    /**
     * @see org.carapaceproxy.server.mapper.MapResult.Action#PROXY
     */
    public static final String TYPE_PROXY = "proxy";
    /**
     * @see org.carapaceproxy.server.mapper.MapResult.Action#CACHE
     */
    public static final String TYPE_CACHE = "cache";
    /**
     * @see org.carapaceproxy.server.mapper.MapResult.Action#STATIC
     */
    public static final String TYPE_STATIC = "static";
    /**
     * @see org.carapaceproxy.server.mapper.MapResult.Action#ACME_CHALLENGE
     */
    public static final String TYPE_ACME_CHALLENGE = "acme-challenge";
    /**
     * @see org.carapaceproxy.server.mapper.MapResult.Action#REDIRECT
     */
    public static final String TYPE_REDIRECT = "redirect";

    private final String id;
    private final String type;
    private final String director;
    private final String file;
    private final int errorCode;
    @Setter(value = AccessLevel.NONE)
    private List<CustomHeader> customHeaders = Collections.emptyList(); // it's a list to keep ordering
    private String redirectLocation;
    private String redirectProto;
    private String redirectHost;
    private int redirectPort;
    private String redirectPath;

    public ActionConfiguration(String id, String type, String director, String file, int errorcode) {
        this.id = id;
        this.type = type;
        this.director = director;
        this.file = file;
        this.errorCode = errorcode;
    }

    public ActionConfiguration setCustomHeaders(List<CustomHeader> customHeaders) {
        this.customHeaders = Collections.unmodifiableList(customHeaders == null ? new ArrayList<>() : customHeaders);
        return this;
    }

}
