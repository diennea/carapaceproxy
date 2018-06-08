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
package nettyhttpproxy.server.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A director is a group of backends
 * @author enrico.olivelli
 */
public class DirectorConfiguration {
    private final String id;
    private final List<String> backends = new ArrayList<>();

    public DirectorConfiguration(String id) {
        this.id = id;
    }
    
    public void addBackend(String id) {
        if (!backends.contains(id)) {
            backends.add(id);
        }
    }

    public String getId() {
        return id;
    }

    public List<String> getBackends() {
        // no copy for efficiency, at runtime this bean is immutable
        return backends;
    }
    
    
}
