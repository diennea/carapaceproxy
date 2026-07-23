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
 * Configuration of a custom ACME provider (any RFC 8555 compliant CA).
 * <p>
 * The built-in {@link #DEFAULT_PROVIDER_NAME} provider (Let's Encrypt) is always available
 * and cannot be redefined; custom providers are configured with the {@code acme.<n>.*}
 * property family and referenced by certificates via {@code certificate.<n>.provider}.
 *
 * @param name the provider name, unique across the configuration
 * @param url  the ACME directory URL of the CA
 * @param kid  the key identifier for External Account Binding, if the CA requires it
 * @param hmac the base64-encoded MAC key for External Account Binding, if the CA requires it
 */
public record AcmeProviderConfiguration(String name, String url, String kid, String hmac) {

    public static final String DEFAULT_PROVIDER_NAME = "letsencrypt";

    public boolean hasExternalAccountBinding() {
        return kid != null && !kid.isEmpty();
    }
}
