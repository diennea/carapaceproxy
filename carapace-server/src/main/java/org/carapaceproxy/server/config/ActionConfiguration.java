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
import org.carapaceproxy.server.mapper.CustomHeader;

/**
 * Action
 */
public class ActionConfiguration {

    public static final String TYPE_PROXY = "proxy";
    public static final String TYPE_CACHE = "cache";
    public static final String TYPE_STATIC = "static";
    public static final String TYPE_ACME_CHALLENGE = "acme-challenge";
    public static final String TYPE_REDIRECT = "redirect";

    private final String id;
    private final String type;
    private final String director;
    private final String file;
    private final int errorcode;
    private List<CustomHeader> customHeaders = Collections.EMPTY_LIST; // it's a list to keep ordering
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
        this.errorcode = errorcode;
    }

    public String getDirector() {
        return director;
    }

    public int getErrorcode() {
        return errorcode;
    }

    public String getFile() {
        return file;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public ActionConfiguration setCustomHeaders(List<CustomHeader> customHeaders) {
        this.customHeaders = Collections.unmodifiableList(customHeaders == null ? new ArrayList() : customHeaders);
        return this;
    }

    public List<CustomHeader> getCustomHeaders() {
        return customHeaders;
    }

    public ActionConfiguration setRedirectLocation(String redirectLocation) {
        this.redirectLocation = redirectLocation;
        return this;
    }

    public String getRedirectLocation() {
        return redirectLocation;
    }

    public ActionConfiguration setRedirectProto(String redirectProto) {
        this.redirectProto = redirectProto;
        return this;
    }

    public String getRedirectProto() {
        return redirectProto;
    }

    public ActionConfiguration setRedirectHost(String redirectHost) {
        this.redirectHost = redirectHost;
        return this;
    }

    public String getRedirectHost() {
        return redirectHost;
    }

    public ActionConfiguration setRedirectPort(int redirectPort) {
        this.redirectPort = redirectPort;
        return this;
    }

    public int getRedirectPort() {
        return redirectPort;
    }

    public ActionConfiguration setRedirectPath(String redirectPath) {
        this.redirectPath = redirectPath;
        return this;
    }

    public String getRedirectPath() {
        return redirectPath;
    }

    @Override
    public String toString() {
        return "ActionConfiguration{" + "id=" + id + ", type=" + type + ", director=" + director + ", file=" + file + ", errorcode=" + errorcode + ", customHeaders=" + customHeaders + ", redirectLocation=" + redirectLocation + ", redirectProto=" + redirectProto + ", redirectHost=" + redirectHost + ", redirectPort=" + redirectPort + ", redirectPath=" + redirectPath + '}';
    }

}
