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

import com.sun.jersey.multipart.FormDataParam;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.configstore.PropertiesConfigurationStore;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.certiticates.DynamicCertificateState;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certiticates.DynamicCertificateState.WAITING;
import org.carapaceproxy.server.certiticates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;

/**
 * Access to certificates
 *
 * @author matteo.minardi
 */
@Path("/certificates")
@Produces("application/json")
public class CertificatesResource {

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class CertificateBean {

        private final String id;
        private final String hostname;
        private final boolean dynamic;
        private String status;
        private final String sslCertificateFile;

        public CertificateBean(String id, String hostname, boolean dynamic, String sslCertificateFile) {
            this.id = id;
            this.hostname = hostname;
            this.dynamic = dynamic;
            this.sslCertificateFile = sslCertificateFile;
        }

        public String getId() {
            return id;
        }

        public String getHostname() {
            return hostname;
        }

        public boolean isDynamic() {
            return dynamic;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getSslCertificateFile() {
            return sslCertificateFile;
        }
    }

    @GET
    @Path("/")
    public Map<String, CertificateBean> getAllCertificates() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificateManager();
        Map<String, CertificateBean> res = new HashMap<>();
        for (Map.Entry<String, SSLCertificateConfiguration> certificateEntry : conf.getCertificates().entrySet()) {
            SSLCertificateConfiguration certificate = certificateEntry.getValue();
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.isDynamic(),
                    certificate.getFile()
            );

            if (certificate.isDynamic()) {
                DynamicCertificateState state = dynamicCertificateManager.getStateOfCertificate(certBean.getId());
                certBean.setStatus(stateToStatusString(state));
            }
            res.put(certificateEntry.getKey(), certBean);
        }

        return res;
    }

    @GET
    @Path("{certId}")
    public CertificateBean getCertificateById(@PathParam("certId") String certId) {
        return findCertificateById(certId);
    }

    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Path("{certId}/download")
    public Response downloadCertificateById(@PathParam("certId") String certId) throws GeneralSecurityException {
        CertificateBean cert = findCertificateById(certId);
        byte[] data = new byte[0];
        if (cert != null && cert.isDynamic()) {
            HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
            DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificateManager();
            data = dynamicCertificateManager.getCertificateForDomain(cert.hostname);
        }

        return Response
                .ok(data, MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + cert.hostname + ".p12")
                .build();
    }

    private CertificateBean findCertificateById(String certId) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        Map<String, SSLCertificateConfiguration> certificateList = conf.getCertificates();
        if (certificateList.containsKey(certId)) {
            SSLCertificateConfiguration certificate = certificateList.get(certId);
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.isDynamic(),
                    certificate.getFile()
            );

            if (certificate.isDynamic()) {
                DynamicCertificateState state = server.getDynamicCertificateManager().getStateOfCertificate(certBean.getId());
                certBean.setStatus(stateToStatusString(state));
            }
            return certBean;
        }

        return null;
    }

    static String stateToStatusString(DynamicCertificateState state) {
        if (state == null) {
            return "unknown";
        }
        switch (state) {
            case WAITING:
                return "waiting"; // certificate waiting for issuing/renews
            case VERIFYING:
                return "verifying"; // challenge verification by LE pending
            case VERIFIED:
                return "verified"; // challenge succeded
            case ORDERING:
                return "ordering"; // certificate order pending
            case REQUEST_FAILED:
                return "request failed"; // challenge/order failed
            case AVAILABLE:
                return "available";// certificate available(saved) and not expired
            case EXPIRED:     // certificate expired
                return "expired";
            default:
                return "unknown";
        }
    }

    @POST
    @Path("{domain}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadCertificate(@PathParam("domain") String domain,
            @FormDataParam("file") InputStream uploadedInputStream) throws Exception {

        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        ConfigurationStore store = server.getDynamicConfigurationStore();        

        // Salvo il certificato su db
        String encodedChain = Base64.getEncoder().encodeToString(uploadedInputStream.readAllBytes());
        CertificateData cert = new CertificateData(domain, "", encodedChain, AVAILABLE.name(), "", "", true);
        store.saveCertificate(cert);       

        // Aggiungo il nuovo certificato alla config (se non c'è già)
        Properties props = store.asProperties(null);
        if (!store.anyPropertyMatches("certificate\\.[0-9]+\\.hostname=" + domain)) {
            String prefix = "certificate." + store.nextIndexFor("certificate") + ".";
            props.setProperty(prefix + "hostname", domain);
            props.setProperty(prefix + "mode", "manual");
        }

        // Ricarico la configurazione
        server.applyDynamicConfigurationFromAPI(new PropertiesConfigurationStore(props));

        return Response.status(200).entity("Certificate saved.").build();
    }

}
