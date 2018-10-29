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
package nettyhttpproxy.configstore;

import nettyhttpproxy.server.config.ConfigurationNotValidException;

/**
 *
 * @author matteo.minardi
 */
public class ConfigurationStoreUtils {
    
    public static int getInt(String key, int defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            return Integer.parseInt(properties.getProperty(key, defaultValue + ""));
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }

    public static long getLong(String key, long defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            return Long.parseLong(properties.getProperty(key, defaultValue + ""));
        } catch (NumberFormatException err) {
            throw new ConfigurationNotValidException("Invalid integer value '" + property + "' for parameter '" + key + "'");
        }
    }
    
    public static String getClassname(String key, String defaultValue, ConfigurationStore properties) throws ConfigurationNotValidException {
        String property = properties.getProperty(key, defaultValue + "");
        try {
            Class.forName(property, true, Thread.currentThread().getContextClassLoader());
            return property;
        } catch (ClassNotFoundException err) {
            throw new ConfigurationNotValidException("Invalid class value '" + property + "' for parameter '" + key + "' : " + err);
        }
    }
    
}
