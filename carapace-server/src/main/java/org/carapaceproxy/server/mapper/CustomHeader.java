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
package org.carapaceproxy.server.mapper;

/**
 *
 * Custom Header to add/set/remove in HttpResponses
 * 
 * @author paolo.venturi
 */
public final class CustomHeader {

    // ADD: to append the header
    // SET: to set the header as the only one
    // REMOVE: to remove the header
    public static enum HeaderMode {
        HEADER_MODE_ADD, HEADER_MODE_SET, HEADER_MODE_REMOVE
    }

    private final String name;
    private final String value;
    private final HeaderMode mode;

    public CustomHeader(String name, String value, HeaderMode mode) {
        this.name = name;
        this.value = value;
        this.mode = mode;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public HeaderMode getMode() {
        return mode;
    }
}
