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
package org.carapaceproxy.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * @author paolo.venturi
 */
public final class CarapaceLogger {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CarapaceLogger.class);

    private static boolean loggingDebugEnabled = false;

    private CarapaceLogger() {
    }

    public static boolean isLoggingDebugEnabled() {
        return loggingDebugEnabled;
    }

    public static void setLoggingDebugEnabled(boolean loggingDebugEnabled) {
        CarapaceLogger.loggingDebugEnabled = loggingDebugEnabled;
    }

    /**
     * This method assumes that {@link Level#DEBUG debug log level} is disabled.
     * If {@link #setLoggingDebugEnabled(boolean) Carapace log level was enabled},
     * forwards the log event to the {@link Level#INFO info log level} instead of using the debug level.
     *
     * @param s the format string
     * @param o a list of arguments
     */
    public static void debug(String s, Object... o) {
        (loggingDebugEnabled ? LOG.atInfo() : LOG.atDebug()).log(s, o);
    }
}
