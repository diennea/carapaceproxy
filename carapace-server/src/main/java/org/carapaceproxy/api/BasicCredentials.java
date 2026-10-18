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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringTokenizer;

/**
 * Credentials of an HTTP Basic {@code Authorization} header.
 *
 * @param user     the user name, trimmed
 * @param password the password, trimmed
 */
public record BasicCredentials(String user, String password) {

    private static final String SCHEME = "Basic";

    /**
     * Parses an {@code Authorization} header of the Basic scheme.
     *
     * @param authorizationHeader the header value, may be {@code null}
     * @return the credentials, or {@code null} if the header is missing or is not valid Basic credentials
     */
    public static BasicCredentials parse(final String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        final StringTokenizer tokenizer = new StringTokenizer(authorizationHeader);
        if (tokenizer.countTokens() != 2 || !tokenizer.nextToken().equalsIgnoreCase(SCHEME)) {
            return null;
        }
        final String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(tokenizer.nextToken().trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        final int position = credentials.indexOf(':');
        if (position < 0) {
            return null;
        }
        return new BasicCredentials(credentials.substring(0, position).trim(), credentials.substring(position + 1).trim());
    }

    /**
     * Keeps the password out of logs and error messages.
     *
     * @return the user name, password redacted
     */
    @Override
    public String toString() {
        return "BasicCredentials[user=" + user + ", password=***]";
    }
}
