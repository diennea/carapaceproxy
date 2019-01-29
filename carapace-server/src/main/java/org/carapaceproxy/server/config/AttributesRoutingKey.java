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

import java.util.Collections;
import java.util.Map;

public class AttributesRoutingKey implements RoutingKey {

    private final Map<String, String> attributes;

    public static final AttributesRoutingKey EMPTY = new AttributesRoutingKey(Collections.emptyMap());

    public AttributesRoutingKey(Map<String, String> attributes) {
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    @Override
    public String getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "{" + attributes + '}';
    }

}
