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

import java.util.List;
import org.carapaceproxy.server.mapper.EndpointMapper;

/**
 * Chooses the backend which can serve the request
 */
public interface BackendSelector {

    List<String> selectBackends(String userId, String sessionId, String director);

    /**
     * Functional interface that models a factory for selectors given the mapper they should be applied to.
     */
    @FunctionalInterface
    interface SelectorFactory {

        BackendSelector build(EndpointMapper endpointMapper);
    }
}
