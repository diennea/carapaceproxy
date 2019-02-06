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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;

/**
 * Access to proxy cache
 *
 * @author enrico.olivelli
 */
@Path("/config")
@Produces("application/json")
public class ConfigResource {

    private static final Logger LOG = Logger.getLogger(ConfigResource.class.getName());
    private static final String CONFIGURATION_DUMPING_FILENAME = "server.dynamic.config";

    @javax.ws.rs.core.Context
    ServletContext context;

    @GET
    public Response dump() {
        StreamingOutput configStream = (output) -> {
            try {
                HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
                ConfigurationStore configStore = server.getDynamicConfigurationStore();
                String lineSeparator = configStore.getProperty("line.separator", "\n");
                Set<String> props = new TreeSet();
                configStore.forEach((k, v) -> {
                    props.add (k + "=" + v);
                });
                StringBuilder builder = new StringBuilder();
                props.forEach(p -> {
                    builder.append(p);
                    builder.append(lineSeparator);
                });
                byte[] data = builder.toString().getBytes("UTF-8");
                output.write(data);
                output.flush();
            } catch (Exception e) {
                throw new WebApplicationException("Unable to dump current configuration.");
            }
        };
        LOG.info("Dumped current configuration from API");
        return Response.ok(configStream, MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + CONFIGURATION_DUMPING_FILENAME)
                .build();
    }

    @Path("/validate")
    @Consumes(value = "text/plain")
    @POST
    public ConfigurationValidationResult validate(String newConfiguration) {
        LOG.info("Validating configuration from API:");
        LOG.info(newConfiguration);

        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            dumpAndValidateInputConfiguration(simpleStore);
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
        LOG.info("Apply configuration from API:");
        LOG.info(newConfiguration);
        LOG.info("**");
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            dumpAndValidateInputConfiguration(simpleStore);
            server.applyDynamicConfiguration(simpleStore);
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

    private void dumpAndValidateInputConfiguration(PropertiesConfigurationStore simpleStore) throws ConfigurationNotValidException {
        int[] count = new int[]{0};
        simpleStore.forEach((k, v) -> {
            LOG.log(Level.INFO, "{0} -> {1}", new Object[]{k, v});
            count[0]++;
        });
        LOG.log(Level.INFO, "Number of entries: " + count[0]);
        if (count[0] == 0) {
            throw new ConfigurationNotValidException("No entries in the new configuration ?");
        }
    }

    public static final class ConfigurationValidationResult {

        private final boolean ok;
        private final String error;

        public static final ConfigurationValidationResult OK = new ConfigurationValidationResult(true, "");

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

        public static final ConfigurationChangeResult OK = new ConfigurationChangeResult(true, "");

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
