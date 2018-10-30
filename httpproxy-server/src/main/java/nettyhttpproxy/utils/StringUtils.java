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
package nettyhttpproxy.utils;

/**
 *
 * @author matteo.minardi
 */
public class StringUtils {

    private static String htmlEncodeCharacters(String s) {
        StringBuilder res = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&':
                    res.append("&amp;");
                    break;
                case '<':
                    res.append("&lt;");
                    break;
                case '>':
                    res.append("&gt;");
                    break;
                case '\"':
                    res.append("&quot;");
                    break;
                default:
                    res.append(c);
            }
        }
        return res.toString();
    }

    public static String htmlEncode(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return htmlEncodeCharacters(s);
    }

    public static String trimToNull(final String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        return str.trim();
    }

}
