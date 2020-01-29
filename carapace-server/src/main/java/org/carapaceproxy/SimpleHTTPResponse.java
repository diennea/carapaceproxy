/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy;

/**
 * Static response to sent to clients.
 *
 * @author paolo.venturi
 */
public class SimpleHTTPResponse {

    private final int errorcode;
    private final String resource;

    public SimpleHTTPResponse(int errorcode, String resource) {
        this.errorcode = errorcode;
        this.resource = resource;
    }

    public int getErrorcode() {
        return errorcode;
    }

    public String getResource() {
        return resource;
    }

    public static final SimpleHTTPResponse NOT_FOUND(String res) {
        return new SimpleHTTPResponse(404, res);
    }

    public static final SimpleHTTPResponse INTERNAL_ERROR(String res) {
        return new SimpleHTTPResponse(500, res);
    }

    @Override
    public String toString() {
        return "SimpleHTTPResponse{" + "errorcode=" + errorcode + ", resource=" + resource + '}';
    }

}
