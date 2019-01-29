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

/**
 * Action
 */
public class ActionConfiguration {

    public static final String TYPE_PROXY = "proxy";
    public static final String TYPE_CACHE = "cache";
    public static final String TYPE_STATIC = "static";
    public static final String TYPE_ACME_CHALLENGE = "acme-challenge";

    private final String id;
    private final String type;
    private final String file;
    private final String director;
    private final int errorcode;

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

    @Override
    public String toString() {
        return "ActionConfiguration{" + "id=" + id + ", type=" + type + ", file=" + file + ", errorcode=" + errorcode + '}';
    }

}
