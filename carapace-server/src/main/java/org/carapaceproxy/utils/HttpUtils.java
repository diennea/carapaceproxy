package org.carapaceproxy.utils;

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

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import io.netty.handler.codec.http.HttpVersion;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import reactor.netty.http.HttpProtocol;

/**
 * HTTP utilities
 *
 * @author paolo.venturi
 */
public class HttpUtils {

    private static final ZoneId GMT = ZoneId.of("GMT");

    public static String formatDateHeader(java.util.Date date) {
        return RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(date.toInstant(), GMT));
    }

    public static HttpProtocol toHttpProtocol(final HttpVersion httpVersion, final boolean ssl) {
        return switch (httpVersion.majorVersion()) {
            case 1 -> HttpProtocol.HTTP11;
            case 2 -> ssl ? HttpProtocol.H2 : HttpProtocol.H2C;
            default -> throw new IllegalStateException("Unexpected HTTP Protocol: " + httpVersion);
        };
    }
}
