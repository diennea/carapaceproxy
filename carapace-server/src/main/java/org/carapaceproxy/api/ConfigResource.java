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
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import org.carapaceproxy.api.response.SimpleResponse;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.configstore.StoreNotReadyException;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.RestoreInProgressException;
import org.carapaceproxy.utils.CarapaceVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access to proxy cache
 *
 * @author enrico.olivelli
 */
@Path("/config")
@Produces("application/json")
public class ConfigResource {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigResource.class);

    @javax.ws.rs.core.Context
    ServletContext context;

    @GET
    @Produces("text/plain")
    public String dump() {
        LOG.info("Dumping current configuration from API");
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        return server.getDynamicConfigurationStore().toStringConfiguration();
    }

    @Path("/validate")
    @Consumes(value = "text/plain")
    @POST
    public Response validate(String newConfiguration) {
        LOG.info("Validating configuration from API:");
        LOG.info(newConfiguration);

        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            dumpAndValidateInputConfiguration(simpleStore);
            server.buildValidConfiguration(simpleStore);
            return SimpleResponse.ok();
        } catch (ConfigurationNotValidException | RuntimeException err) {
            return SimpleResponse.error(err);
        }
    }

    public static PropertiesConfigurationStore buildStore(String newConfiguration) throws ConfigurationNotValidException {
        if (newConfiguration == null || newConfiguration.trim().isEmpty()) {
            throw new ConfigurationNotValidException("Invalid empty configuration");
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(newConfiguration));
        } catch (IOException err) {
            throw new ConfigurationNotValidException("Invalid properties file: " + err.getMessage());
        }
        return new PropertiesConfigurationStore(properties);
    }

    @Path("/apply")
    @Consumes(value = "text/plain")
    @POST
    public Response apply(String newConfiguration) {
        LOG.info("Apply configuration from API:");
        LOG.info(newConfiguration);
        LOG.info("**");
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            PropertiesConfigurationStore simpleStore = buildStore(newConfiguration);
            dumpAndValidateInputConfiguration(simpleStore);
            server.applyDynamicConfigurationFromAPI(simpleStore);
            return SimpleResponse.ok();
        } catch (ConfigurationNotValidException
                | ConfigurationChangeInProgressException
                | RuntimeException err) {
            return SimpleResponse.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return SimpleResponse.error(err);
        }
    }

    @Path("/maintenance")
    @Consumes(value = "text/plain")
    @POST
    public ConfigurationChangeResult setupMaintenanceMode(@QueryParam("enable") boolean value) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            server.updateMaintenanceMode(value);
            return ConfigurationChangeResult.response(server.getCurrentConfiguration().isMaintenanceModeEnabled(), "");
        } catch (RestoreInProgressException err) {
            throw new WebApplicationException(SimpleResponse.serviceUnavailable(err));
        } catch (ConfigurationChangeInProgressException err) {
            return ConfigurationChangeResult.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return ConfigurationChangeResult.error(err);
        }
    }

    @Path("/maintenance")
    @GET
    public ConfigurationChangeResult getMaintenanceStatus() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        return ConfigurationChangeResult.response(server.getCurrentConfiguration().isMaintenanceModeEnabled(), "");
    }

    /**
     * Exports the whole dynamic state as a {@link StateArchive} download. It holds every private key and secret.
     *
     * @param authorization the Basic credentials of the caller, logged by user name
     * @param request       the request, for the client address in the log
     * @return the JSON archive; 503 with {@code Retry-After} if the store does not answer now; 500 if the store fails
     */
    @Path("/backup")
    @GET
    public Response backup(@HeaderParam("Authorization") String authorization, @javax.ws.rs.core.Context HttpServletRequest request) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            final StateArchive archive = StateArchive.of(server.backupState());
            final String filename = "carapace-state-" + archive.createdAt().replace(":", "") + ".json";
            final byte[] json = archive.toJson();
            // every secret leaves with it: record who took it
            final BasicCredentials credentials = BasicCredentials.parse(authorization);
            LOG.info("State backup exported by user {} from {}, {} configuration entries, {} certificates",
                    credentials != null ? credentials.user() : null, request.getRemoteAddr(),
                    archive.config().size(), archive.certificates().size());
            return Response.ok(json)
                    // every private key is in there: no copy in any cache
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        } catch (StoreNotReadyException err) {
            LOG.warn("State backup not taken: {}", err.getMessage());
            return SimpleResponse.serviceUnavailable(err);
        } catch (IOException | RuntimeException err) {
            LOG.error("State backup failed", err);
            return internalError(err);
        }
    }

    /**
     * Replaces the whole dynamic state with a {@link StateArchive} on every peer; ACME challenge tokens are kept.
     *
     * @param archive            the JSON archive
     * @param acceptNewerVersion whether to accept an archive taken on a newer Carapace, at the caller's own risk
     * @param authorization      the Basic credentials of the caller, that the restored user realm must still accept
     * @return 200 with a {@link HttpProxyServer.RestoreResult}, 500 with it if not applied here or the outcome is unknown;
     * with nothing written, 422 if refused, 503 if busy or not ready, 500 if the store or ZooKeeper fail
     */
    @Path("/restore")
    @Consumes("application/json")
    @POST
    public Response restore(InputStream archive,
                            @QueryParam("acceptNewerVersion") boolean acceptNewerVersion,
                            @HeaderParam("Authorization") String authorization) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            // AuthAPIRequestsFilter has already parsed and checked them
            final BasicCredentials credentials = BasicCredentials.parse(authorization);
            final StoreSnapshot snapshot = StateArchive.read(archive).toSnapshot(CarapaceVersion.VERSION, acceptNewerVersion);
            final HttpProxyServer.RestoreResult result = server.restoreState(snapshot, credentials.user(), credentials.password());
            return Response.status(result.ok() ? Response.Status.OK : Response.Status.INTERNAL_SERVER_ERROR).entity(result).build();
        } catch (ConfigurationChangeInProgressException | StoreNotReadyException err) {
            LOG.warn("State restore not started: {}", err.getMessage());
            return SimpleResponse.serviceUnavailable(err);
        } catch (ConfigurationNotValidException err) {
            LOG.warn("State restore refused: {}", err.getMessage());
            return SimpleResponse.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return SimpleResponse.serviceUnavailable(err);
        } catch (RuntimeException err) {
            LOG.error("State restore failed before the commit", err);
            return internalError(err);
        }
    }

    /**
     * A 500 answer, for a failure of the store or of ZooKeeper.
     *
     * @param err the failure
     * @return the response
     */
    private static Response internalError(Exception err) {
        return Response.fromResponse(SimpleResponse.error(err)).status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    private void dumpAndValidateInputConfiguration(PropertiesConfigurationStore simpleStore) throws ConfigurationNotValidException {
        int[] count = new int[]{0};
        simpleStore.forEach((k, v) -> {
            LOG.info("{} -> {}", k, v);
            count[0]++;
        });
        LOG.info("Number of entries: {}", count[0]);
        if (count[0] == 0) {
            throw new ConfigurationNotValidException("No entries in the new configuration ?");
        }
    }

    public static final class ConfigurationChangeResult {

        private final boolean ok;
        private final String error;

        public static final ConfigurationChangeResult OK = new ConfigurationChangeResult(true, "");

        public static ConfigurationChangeResult error(Throwable error) {
            StringWriter w = new StringWriter();
            error.printStackTrace(new PrintWriter(w));
            return new ConfigurationChangeResult(false, w.toString());
        }

        public static ConfigurationChangeResult response(boolean status, String error) {
            return new ConfigurationChangeResult(status, error);
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
