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
package nettyhttpproxy.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.server.mapper.StandardEndpointMapper;

public class Launcher {

    static {
        // see https://github.com/netty/netty/pull/7650
        if (System.getProperty("io.netty.tryReflectionSetAccessible") == null) {
            System.setProperty("io.netty.tryReflectionSetAccessible", "true");
        }
    }
    
    public static void main(String... args) {
        try {
            String configFile = "conf/server.properties";
            if (args.length > 0) {
                configFile = args[0];
            }
            File file = new File(configFile).getAbsoluteFile();
            File basePath = file.getParentFile().getParentFile();
            System.out.println("Reading configuration from " + file.getAbsolutePath());
            Properties properties = new Properties();
            try (FileInputStream i = new FileInputStream(file)) {
                properties.load(i);
            }
            StandardEndpointMapper mapper = new StandardEndpointMapper();
            mapper.configure(properties);
            HttpProxyServer server = new HttpProxyServer(mapper, basePath);
            server.configure(properties);
            server.start();
            server.startMetrics();
            System.out.println("HTTP Proxy server started, pid: "+java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
            Thread.sleep(Integer.MAX_VALUE);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }
}
