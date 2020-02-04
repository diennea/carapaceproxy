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

import static org.carapaceproxy.configstore.ConfigurationStoreUtils.base64DecodeCertificateChain;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
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
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import java.util.Set;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
import org.carapaceproxy.utils.CertificatesUtils;
import static org.carapaceproxy.utils.APIUtils.certificateStateToString;
import static org.carapaceproxy.utils.APIUtils.certificateModeToString;
import static org.carapaceproxy.utils.APIUtils.stringToCertificateMode;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    }

    @GET
    @Path("/")
    public Map<String, CertificateBean> getAllCertificates() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificatesManager();
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

            if (certificate.isDynamic()) {
                certBean.setStatus(certificateStateToString(null)); // unknown
                try {
                    CertificateData cert = dynamicCertificateManager.getCertificateDataForDomain(certificate.getHostname());
                    if (cert != null) {
                        certBean.setStatus(certificateStateToString(cert.getState()));
                        certBean.setExpiringDate(extractExpiringDate(cert));
                        if (!cert.isManual()) {
                            certBean.setDaysBeforeRenewal(cert.getDaysBeforeRenewal() + "");
                        }
                    }
                } catch (GeneralSecurityException e) {
                    LOG.log(Level.SEVERE, "Unable to read Keystore for certificate {0}. Reason: {1}", new Object[]{certificate.getId(), e});
                }
            }
            res.put(certificateEntry.getKey(), certBean);
        }

        return res;
    }

    private static String extractExpiringDate(CertificateData cert) throws GeneralSecurityException {
        Certificate[] chain = base64DecodeCertificateChain(cert.getChain());
        if (chain != null && chain.length > 0) {
            return ((X509Certificate) chain[0]).getNotAfter().toString();
        }
        return "";
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
                    certificateModeToString(certificate.getMode()),
                    certificate.isDynamic(),
                    certificate.getFile()
            );

            if (certificate.isDynamic()) {
                certBean.setStatus(certificateStateToString(null)); // unknown
                try {
                    CertificateData cert = server.getDynamicCertificatesManager().getCertificateDataForDomain(certificate.getHostname());
                    certBean.setStatus(certificateStateToString(cert.getState()));
                    certBean.setExpiringDate(extractExpiringDate(cert));
                    if (!cert.isManual()) {
                        certBean.setDaysBeforeRenewal(cert.getDaysBeforeRenewal() + "");
                    }
                } catch (GeneralSecurityException e) {
                    LOG.log(Level.SEVERE, "Unable to read Keystore for certificate {0}. Reason: {1}", new Object[]{certificate.getId(), e});
                }
            }
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
            boolean available = false;
            if (data != null && data.length > 0) {
                encodedData = Base64.getEncoder().encodeToString(data);
                available = true;
                state = AVAILABLE;
            }
            CertificateData cert = new CertificateData(domain, "", encodedData, state, "", "", available);
            cert.setManual(MANUAL.equals(certType));
            cert.setDaysBeforeRenewal(daysbeforerenewal != null ? daysbeforerenewal : DEFAULT_DAYS_BEFORE_RENEWAL);
            HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
            server.updateDynamicCertificateForDomain(cert);

            return Response.status(200).entity("SUCCESS: Certificate saved").build();
        }
    }

}
