package nettyhttpproxy;

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
import io.netty.handler.codec.http.HttpRequest;
import nettyhttpproxy.server.backends.BackendHealthManager;

public class TestEndpointMapper extends EndpointMapper {

    private final String host;
    private final int port;
    private final boolean cacheAll;

    public TestEndpointMapper(String host, int port) {
        this(host, port, false);
    }

    public TestEndpointMapper(String host, int port, boolean cacheAll) {
        this.host = host;
        this.port = port;
        this.cacheAll = cacheAll;
    }

    @Override
    public MapResult map(HttpRequest request, BackendHealthManager backendHealthManager) {
        String uri = request.uri();
        if (uri.contains("not-found")) {
            return MapResult.NOT_FOUND;
        } else if (uri.contains("debug")) {
            return new MapResult(null, 0, MapResult.Action.SYSTEM);
        } else if (cacheAll) {
            return new MapResult(host, port, MapResult.Action.CACHE);
        } else {
            return new MapResult(host, port, MapResult.Action.PROXY);
        }
    }
}
