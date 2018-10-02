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
package nettyhttpproxy.api;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import nettyhttpproxy.configstore.PropertiesConfigurationStore;
import nettyhttpproxy.server.HttpProxyServer;
import nettyhttpproxy.server.RuntimeServerConfiguration;
import nettyhttpproxy.server.config.ConfigurationChangeInProgressException;
import nettyhttpproxy.server.config.ConfigurationNotValidException;

/**
 * Access to proxy cache
 *
 * @author enrico.olivelli
 */
@Path("/config")
@Produces("application/json")
public class ConfigResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    @Path("/validate")
    @Consumes(value = "text/plain")
    @POST
    public ConfigurationValidationResult validate(String newConfiguration) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            server.buildValidConfiguration(simpleStore);
            return ConfigurationValidationResult.OK;
        } catch (ConfigurationNotValidException | RuntimeException err) {
            return ConfigurationValidationResult.error(err);
        }
    }

    private PropertiesConfigurationStore buildStore(String newConfiguration) throws ConfigurationNotValidException {
        if (newConfiguration == null || newConfiguration.trim().isEmpty()) {
            throw new ConfigurationNotValidException("Invalid empty configuration");
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(newConfiguration));
        } catch (IOException err) {
            throw new ConfigurationNotValidException("Invalid properties file: " + err.getMessage());
        }
        PropertiesConfigurationStore simpleStore = new PropertiesConfigurationStore(properties);
        return simpleStore;
    }

    @Path("/apply")
    @Consumes(value = "text/plain")
    @POST
    public ConfigurationChangeResult apply(String newConfiguration) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            RuntimeServerConfiguration validatedConfiguration = server.buildValidConfiguration(simpleStore);
            server.applyDynamicConfiguration(validatedConfiguration, simpleStore);
            return ConfigurationChangeResult.OK;
        } catch (ConfigurationNotValidException
                | ConfigurationChangeInProgressException
                | RuntimeException err) {
            return ConfigurationChangeResult.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return ConfigurationChangeResult.error(err);
        }
    }

    public static final class ConfigurationValidationResult {

        private final boolean ok;
        private final String error;

        public static final ConfigurationValidationResult OK = new ConfigurationValidationResult(true, null);

        public static final ConfigurationValidationResult error(Throwable error) {
            StringWriter w = new StringWriter();
            error.printStackTrace(new PrintWriter(w));
            return new ConfigurationValidationResult(false, w.toString());
        }

        private ConfigurationValidationResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

    }

    public static final class ConfigurationChangeResult {

        private final boolean ok;
        private final String error;

        public static final ConfigurationChangeResult OK = new ConfigurationChangeResult(true, null);

        public static final ConfigurationChangeResult error(Throwable error) {
            StringWriter w = new StringWriter();
            error.printStackTrace(new PrintWriter(w));
            return new ConfigurationChangeResult(false, w.toString());
        }

        private ConfigurationChangeResult(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

    }

}
