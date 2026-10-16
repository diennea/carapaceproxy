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
import java.util.List;
import java.util.Properties;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import org.carapaceproxy.api.response.SimpleResponse;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.configstore.StoreSnapshot;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
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

    /**
     * Response header of {@link #backup()}: how many dynamic certificates of the backup are not available.
     */
    public static final String DOMAINS_WITHOUT_CERTIFICATE_COUNT_HEADER = "X-Carapace-Domains-Without-Certificate-Count";

    /**
     * Response header of {@link #backup()}: the comma-separated domains whose dynamic certificate is not available.
     */
    public static final String DOMAINS_WITHOUT_CERTIFICATE_HEADER = "X-Carapace-Domains-Without-Certificate";

    // well below the 8 KB response header buffer of Jetty
    private static final int MAX_HEADER_LIST_LENGTH = 4096;

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
     * Exports the whole dynamic state as a {@link StateArchive}, as a file download.
     * <br>
     * The archive holds every private key, the ACME account key included, and every password or secret of the configuration: treat it as a secret.
     * Header {@value #DOMAINS_WITHOUT_CERTIFICATE_COUNT_HEADER} tells how many dynamic certificates of the
     * configuration are not available, so that a restore would issue them again; {@value #DOMAINS_WITHOUT_CERTIFICATE_HEADER}
     * lists them when the list is short enough for a header, the server log always does.
     *
     * @return the JSON archive, or 422 if the state cannot be exported now
     */
    @Path("/backup")
    @GET
    public Response backup() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        try {
            final HttpProxyServer.BackupResult result = server.backupState();
            final StateArchive archive = StateArchive.of(result.snapshot());
            final String filename = "carapace-state-" + archive.createdAt().replace(":", "") + ".json";
            final List<String> domains = result.domainsWithoutCertificate();
            final Response.ResponseBuilder response = Response.ok(archive.toJson())
                    // every private key is in there: no copy in any cache
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header(DOMAINS_WITHOUT_CERTIFICATE_COUNT_HEADER, domains.size());
            final String list = String.join(",", domains);
            if (!domains.isEmpty() && list.length() <= MAX_HEADER_LIST_LENGTH) {
                response.header(DOMAINS_WITHOUT_CERTIFICATE_HEADER, list);
            }
            LOG.info("State backup exported, {} configuration entries, {} certificates",
                    archive.config().size(), archive.certificates().size());
            return response.build();
        } catch (ConfigurationChangeInProgressException | IOException | RuntimeException err) {
            return SimpleResponse.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return SimpleResponse.error(err);
        }
    }

    /**
     * Replaces the whole dynamic state with a {@link StateArchive}: configuration, certificates and key pairs, on
     * every peer. Whatever is not in the archive is removed; ACME challenge tokens are kept.
     *
     * @param archive            the JSON archive
     * @param acceptNewerVersion whether to accept an archive taken on a newer Carapace, at the caller's own risk
     * @param authorization      the Basic credentials of the caller, that the restored user realm must still accept
     * @return 200 with a {@link HttpProxyServer.RestoreResult}; 500 with the same body when the state was stored but
     * this node could not apply it, or when the store failed while writing and the restore must be repeated; 422 with
     * nothing written when the archive is refused or the cluster is not ready
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
        } catch (ConfigurationNotValidException | ConfigurationChangeInProgressException | RuntimeException err) {
            LOG.warn("State restore refused or failed: {}", err.toString());
            return SimpleResponse.error(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return SimpleResponse.error(err);
        }
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
