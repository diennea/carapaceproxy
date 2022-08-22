/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.carapaceproxy.api;

import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.configstore.ConfigurationStoreUtils;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode;
import org.carapaceproxy.utils.CertificatesUtils;

import javax.servlet.ServletContext;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.carapaceproxy.utils.APIUtils.*;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;

/**
 * Access to certificates
 *
 * @author matteo.minardi
 */
@Path("/certificates")
@Produces("application/json")
public class CertificatesResource {

    public static final Set<DynamicCertificateState> AVAILABLE_CERTIFICATES_STATES_FOR_UPLOAD = Set.of(AVAILABLE, WAITING);
    private static final Logger LOG = Logger.getLogger(CertificatesResource.class.getName());

    @javax.ws.rs.core.Context
    ServletContext context;

    public static final class CertificateBean {

        private final String id;
        private final String hostname;
        private final String mode;
        private final boolean dynamic;
        private String status;
        private final String sslCertificateFile;
        private String expiringDate;
        private String daysBeforeRenewal;
        private String serialNumber;

        public CertificateBean(String id, String hostname, String mode, boolean dynamic, String sslCertificateFile) {
            this.id = id;
            this.hostname = hostname;
            this.mode = mode;
            this.dynamic = dynamic;
            this.sslCertificateFile = sslCertificateFile;
        }

        public String getId() {
            return id;
        }

        public String getHostname() {
            return hostname;
        }

        public String getMode() {
            return mode;
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

        public String getExpiringDate() {
            return expiringDate;
        }

        public void setExpiringDate(String expiringDate) {
            this.expiringDate = expiringDate;
        }

        public String getDaysBeforeRenewal() {
            return daysBeforeRenewal;
        }

        public void setDaysBeforeRenewal(String daysBeforeRenewal) {
            this.daysBeforeRenewal = daysBeforeRenewal;
        }

        public String getSerialNumber() {
            return serialNumber;
        }

        public void setSerialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
        }

    }

    @GET
    @Path("/")
    public Map<String, CertificateBean> getAllCertificates() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        DynamicCertificatesManager dCManager = server.getDynamicCertificatesManager();
        Map<String, CertificateBean> res = new HashMap<>();
        for (Map.Entry<String, SSLCertificateConfiguration> certificateEntry : conf.getCertificates().entrySet()) {
            SSLCertificateConfiguration certificate = certificateEntry.getValue();
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificateModeToString(certificate.getMode()),
                    certificate.isDynamic(),
                    certificate.getFile()
            );
            fillCertificateBean(certBean, certificate, dCManager, server);
            res.put(certificateEntry.getKey(), certBean);
        }

        return res;
    }

    private static void fillCertificateBean(CertificateBean bean, SSLCertificateConfiguration certificate, DynamicCertificatesManager dCManager, HttpProxyServer server) {
        try {
            DynamicCertificateState state = null;
            if (certificate.isDynamic()) {
                CertificateData cert = dCManager.getCertificateDataForDomain(certificate.getId());
                if (cert == null) {
                    return;
                }
                state = certificate.isAcme()
                        ? cert.getState()
                        : CertificatesUtils.isCertificateExpired(cert.getExpiringDate(), 0) ? DynamicCertificateState.EXPIRED : DynamicCertificateState.AVAILABLE;
                bean.setExpiringDate(cert.getExpiringDate() != null ? cert.getExpiringDate().toString() : "");
                bean.setSerialNumber(cert.getSerialNumber());
            } else {
                KeyStore keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), server.getBasePath());
                if (keystore == null) {
                    return;
                }
                Certificate[] chain = CertificatesUtils.readChainFromKeystore(keystore);
                if (chain != null && chain.length > 0) {
                    X509Certificate _cert = ((X509Certificate) chain[0]);
                    bean.setExpiringDate(_cert.getNotAfter().toString());
                    bean.setSerialNumber(_cert.getSerialNumber().toString(16).toUpperCase()); // HEX
                    if (!certificate.isAcme()) {
                        state = CertificatesUtils.isCertificateExpired(_cert.getNotAfter(), 0) ? DynamicCertificateState.EXPIRED : DynamicCertificateState.AVAILABLE;
                    }
                }
            }
            if (certificate.isAcme()) {
                bean.setDaysBeforeRenewal(certificate.getDaysBeforeRenewal() + "");
            }
            bean.setStatus(certificateStateToString(state));
        } catch (GeneralSecurityException | IOException ex) {
            LOG.log(Level.SEVERE, "Unable to read Keystore for certificate {0}. Reason: {1}", new Object[]{certificate.getId(), ex});
        }
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
            DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificatesManager();
            data = dynamicCertificateManager.getCertificateForDomain(cert.getId());
        }

        return Response
                .ok(data, MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + cert.getId() + ".p12")
                .build();
    }

    private CertificateBean findCertificateById(String certId) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        SSLCertificateConfiguration certificate = server.getCurrentConfiguration().getCertificates().get(certId);
        DynamicCertificatesManager dCManager = server.getDynamicCertificatesManager();
        if (certificate != null) {
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificateModeToString(certificate.getMode()),
                    certificate.isDynamic(),
                    certificate.getFile()
            );
            fillCertificateBean(certBean, certificate, dCManager, server);
            return certBean;
        }

        return null;
    }

    @POST
    @Path("{domain}/upload")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadCertificate(
            @PathParam("domain") String domain,
            @QueryParam("type") @DefaultValue("manual") String type,
            @QueryParam("daysbeforerenewal") Integer daysbeforerenewal,
            InputStream uploadedInputStream) throws Exception {

        try (InputStream input = uploadedInputStream) {
            // Certificate type (manual | acme)
            CertificateMode certType = stringToCertificateMode(type);
            if (certType == null || STATIC.equals(certType)) {
                return Response.status(422).entity("ERROR: illegal type of certificate. Available: manual, acme").build();
            }
            // Certificate content (optional for acme type)
            byte[] data = input.readAllBytes();
            if (MANUAL.equals(certType) && (data == null || data.length == 0)) {
                return Response.status(422).entity("ERROR: certificate data required for type 'manual'").build();
            }
            if (data != null && data.length > 0 && !CertificatesUtils.validateKeystore(data)) {
                return Response.status(422).entity("ERROR: unable to read uploded certificate").build();
            }

            if (daysbeforerenewal != null) {
                if (CertificateMode.ACME.equals(certType) && daysbeforerenewal < 0) {
                    return Response.status(422).entity("ERROR: param 'daysbeforerenewal' has to be a positive number").build();
                } else if (!CertificateMode.ACME.equals(certType)) {
                    return Response.status(422).entity("ERROR: param 'daysbeforerenewal' available for type 'acme' only").build();
                }
            }

            String encodedData = "";
            DynamicCertificateState state = WAITING;
            if (data != null && data.length > 0) {
                Certificate[] chain = CertificatesUtils.readChainFromKeystore(data);
                PrivateKey key = CertificatesUtils.loadPrivateKey(data, "");
                encodedData = Base64.getEncoder().encodeToString(createKeystore(chain, key));
                state = AVAILABLE;
            }

            CertificateData cert = new CertificateData(domain, "", encodedData, state, "", "");
            cert.setManual(MANUAL.equals(certType));

            cert.setDaysBeforeRenewal(daysbeforerenewal != null ? daysbeforerenewal : DEFAULT_DAYS_BEFORE_RENEWAL);
            HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
            server.updateDynamicCertificateForDomain(cert);

            return Response.status(200).entity("SUCCESS: Certificate saved").build();
        }
    }

}
