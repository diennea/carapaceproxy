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
package org.carapaceproxy.server.mapper.requestmatcher;

/** 
 * Matcher for composing EQUALS expressions give a property-key and the expected value.
 *
 * @author paolo.venturi
 */
public class EqualsRequestMatcher implements RequestMatcher {

    private final String name;
    private final String value;

    public EqualsRequestMatcher(String key, String value) {
        this.name = key.toLowerCase();
        this.value = value;
    }

    @Override
    public boolean matches(MatchingContext context) throws MatchingException {
        return context.getProperty(name).equals(value);
    }

    public String getDescription() {
        return "(" + name + " = " + value + ")";
    }

}
