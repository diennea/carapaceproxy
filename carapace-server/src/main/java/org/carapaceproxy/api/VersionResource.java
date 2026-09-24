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

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.carapaceproxy.utils.CarapaceVersion;

/**
 * Version and commit of the running server, so that clients can tell what is deployed.
 */
@Path("/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

    /**
     * Build information of the server.
     *
     * @param version project version, e.g. {@code 2.4.0-SNAPSHOT}
     * @param commit  abbreviated commit hash the build was made from
     */
    public record VersionBean(String version, String commit) {
    }

    /**
     * @return version and commit of this server
     */
    @GET
    public VersionBean get() {
        return new VersionBean(CarapaceVersion.VERSION, CarapaceVersion.COMMIT);
    }
}
