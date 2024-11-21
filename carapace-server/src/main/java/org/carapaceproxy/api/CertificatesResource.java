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

import static org.carapaceproxy.server.certificates.DynamicCertificateState.AVAILABLE;
import static org.carapaceproxy.server.certificates.DynamicCertificateState.WAITING;
import static org.carapaceproxy.server.certificates.DynamicCertificatesManager.DEFAULT_DAYS_BEFORE_RENEWAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.ACME;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.MANUAL;
import static org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode.STATIC;
import static org.carapaceproxy.utils.APIUtils.certificateModeToString;
import static org.carapaceproxy.utils.APIUtils.certificateStateToString;
import static org.carapaceproxy.utils.APIUtils.stringToCertificateMode;
import static org.carapaceproxy.utils.CertificatesUtils.createKeystore;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.carapaceproxy.api.response.FormValidationResponse;
import org.carapaceproxy.api.response.SimpleResponse;
import org.carapaceproxy.configstore.CertificateData;
import org.carapaceproxy.core.HttpProxyServer;
import org.carapaceproxy.core.RuntimeServerConfiguration;
import org.carapaceproxy.server.certificates.DynamicCertificateState;
import org.carapaceproxy.server.certificates.DynamicCertificatesManager;
import org.carapaceproxy.server.config.ConfigurationChangeInProgressException;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration.CertificateMode;
import org.carapaceproxy.utils.CertificatesUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access to certificates
 *
 * @author matteo.minardi
 */
@Path("/certificates")
@Produces(MediaType.APPLICATION_JSON)
public class CertificatesResource {

    private static final Logger LOG = LoggerFactory.getLogger(CertificatesResource.class);

    @Context
    private ServletContext context;

    @Data
    @AllArgsConstructor
    public static final class CertificatesResponse {

        private final Collection<CertificateBean> certificates;
        private final String localStorePath;

        public CertificatesResponse(final Collection<CertificateBean> certificates, final HttpProxyServer server) {
            this.certificates = certificates;
            this.localStorePath = server.getCurrentConfiguration().getLocalCertificatesStorePath();
        }

    }

    @Data
    public static final class CertificateBean {

        private final String id;
        private final String hostname;
        private final String subjectAltNames;
        private final String mode;
        private final boolean dynamic;
        private String status;
        private final String sslCertificateFile;
        private String expiringDate;
        private String daysBeforeRenewal;
        private String serialNumber;
        private int attemptsCount;
        private String message;

        public CertificateBean(
                final String id,
                final String hostname,
                final Set<String> subjectAltNames,
                final String mode,
                final boolean dynamic,
                final String sslCertificateFile,
                final int attemptsCount,
                final String message
        ) {
            this.id = id;
            this.hostname = hostname;
            this.subjectAltNames = subjectAltNames != null ? String.join(", ", subjectAltNames) : "";
            this.mode = mode;
            this.dynamic = dynamic;
            this.sslCertificateFile = sslCertificateFile;
            this.attemptsCount = attemptsCount;
            this.message = message;
        }

        public CertificateBean(final String id, final String hostname, final Set<String> subjectAltNames, final String mode, final boolean dynamic, final String file) {
            this(id, hostname, subjectAltNames, mode, dynamic, file, 0, "");
        }
    }

    @GET
    @Path("/")
    public CertificatesResponse getAllCertificates() {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        DynamicCertificatesManager dCManager = server.getDynamicCertificatesManager();
        Map<String, CertificateBean> res = new HashMap<>();
        for (Map.Entry<String, SSLCertificateConfiguration> certificateEntry : conf.getCertificates().entrySet()) {
            SSLCertificateConfiguration certificate = certificateEntry.getValue();
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.getSubjectAltNames(),
                    certificateModeToString(certificate.getMode()),
                    certificate.isDynamic(),
                    certificate.getFile()
            );
            fillCertificateBean(certBean, certificate, dCManager, server);
            res.put(certificateEntry.getKey(), certBean);
        }

        return new CertificatesResponse(res.values(), server);
    }

    private static void fillCertificateBean(
            final CertificateBean bean,
            final SSLCertificateConfiguration certificate,
            final DynamicCertificatesManager dCManager,
            final HttpProxyServer server) {
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
                bean.setAttemptsCount(cert.getAttemptsCount());
                bean.setMessage(cert.getMessage());
            } else {
                KeyStore keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), server.getBasePath());
                if (keystore == null) {
                    return;
                }
                Certificate[] chain = CertificatesUtils.readChainFromKeystore(keystore);
                if (chain.length > 0) {
                    X509Certificate _cert = ((X509Certificate) chain[0]);
                    bean.setExpiringDate(_cert.getNotAfter().toString());
                    bean.setSerialNumber(_cert.getSerialNumber().toString(16).toUpperCase()); // HEX
                    if (!certificate.isAcme()) {
                        state = CertificatesUtils.isCertificateExpired(_cert.getNotAfter(), 0)
                                ? DynamicCertificateState.EXPIRED
                                : DynamicCertificateState.AVAILABLE;
                    }
                }
            }
            if (certificate.isAcme()) {
                bean.setDaysBeforeRenewal(certificate.getDaysBeforeRenewal() + "");
            }
            bean.setStatus(certificateStateToString(state));
        } catch (GeneralSecurityException | IOException ex) {
            LOG.error("Unable to read Keystore for certificate {}", certificate.getId(), ex);
        }
    }

    @GET
    @Path("{certId}")
    public CertificatesResponse getCertificateById(@PathParam("certId") final String certId) {
        final var cert = findCertificateById(certId);
        return new CertificatesResponse(
                cert != null ? List.of(cert) : Collections.emptyList(),
                (HttpProxyServer) context.getAttribute("server")
        );
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class CertificateForm {
        private String domain;
        private Set<String> subjectAltNames;
        private String type;
        private int daysBeforeRenewal = DEFAULT_DAYS_BEFORE_RENEWAL;
    }

    @POST
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createCertificate(CertificateForm form) {
        if (form.domain == null || form.domain.isBlank()) {
            return FormValidationResponse.fieldRequired("domain");
        }
        if (form.subjectAltNames != null && form.subjectAltNames.contains(form.domain)) {
            return FormValidationResponse.fieldError(
                    "subjectAltNames",
                    "Subject alternative names cannot include the Domain"
            );
        }
        CertificateMode certType = stringToCertificateMode(form.type);
        if (certType == null || !certType.equals(ACME)) {
            return FormValidationResponse.fieldInvalid("type");
        }
        if (form.daysBeforeRenewal < 0) {
            return FormValidationResponse.fieldInvalid("daysBeforeRenewal");
        }
        if (findCertificateById(form.domain) != null) {
            return FormValidationResponse.fieldConflict("domain");
        }

        final var cert = new CertificateData(form.domain, null, WAITING);
        cert.setSubjectAltNames(form.subjectAltNames);
        cert.setDaysBeforeRenewal(form.daysBeforeRenewal);
        try {
            ((HttpProxyServer) context.getAttribute("server")).updateDynamicCertificateForDomain(cert);
        } catch (Exception e) {
            return FormValidationResponse.error(e);
        }
        return FormValidationResponse.created();
    }

    @DELETE
    @Path("{certId}")
    public Response deleteCertificate(@PathParam("certId") final String certId) {
        final var server = (HttpProxyServer) context.getAttribute("server");
        final var certificates = server.getCurrentConfiguration().getCertificates();
        if (!certificates.containsKey(certId)) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        try {
            server.rewriteConfiguration(it -> it.removeCertificate(certId));
            return SimpleResponse.ok();
        } catch (ConfigurationChangeInProgressException | InterruptedException | ConfigurationNotValidException e) {
            return SimpleResponse.error(e);
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Path("{certId}/download")
    public Response downloadCertificateById(@PathParam("certId") final String certId) {
        CertificateBean cert = findCertificateById(certId);
        if (cert == null || !cert.isDynamic()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificatesManager();
        final var data = dynamicCertificateManager.getCertificateForDomain(cert.getId());
        return Response
                .ok(data, MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + cert.getId() + ".p12")
                .build();
    }

    private CertificateBean findCertificateById(final String certId) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        SSLCertificateConfiguration certificate = server.getCurrentConfiguration().getCertificates().get(certId);
        DynamicCertificatesManager dCManager = server.getDynamicCertificatesManager();
        if (certificate != null) {
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.getSubjectAltNames(),
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
            @PathParam("domain") final String domain,
            @QueryParam("subjectaltnames") final List<String> subjectAltNames,
            @QueryParam("type") @DefaultValue("manual") final String type,
            @QueryParam("daysbeforerenewal") final Integer daysbeforerenewal,
            final InputStream uploadedInputStream) throws Exception {

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

            CertificateData cert = new CertificateData(domain, encodedData, state);
            cert.setManual(MANUAL.equals(certType));
            cert.setSubjectAltNames(Set.copyOf(subjectAltNames));
            cert.setDaysBeforeRenewal(daysbeforerenewal != null ? daysbeforerenewal : DEFAULT_DAYS_BEFORE_RENEWAL);

            ((HttpProxyServer) context.getAttribute("server")).updateDynamicCertificateForDomain(cert);

            return Response.status(200).entity("SUCCESS: Certificate saved").build();
        }
    }

    @POST
    @Path("{domain}/store")
    public Response storeLocalCertificate(@PathParam("domain") final String domain) {
        var server = ((HttpProxyServer) context.getAttribute("server"));
        server.getDynamicCertificatesManager().forceStoreLocalCertificates(domain);
        return SimpleResponse.ok();
    }

    @POST
    @Path("/storeall")
    public Response storeAllCertificates() {
        var server = ((HttpProxyServer) context.getAttribute("server"));
        server.getDynamicCertificatesManager().forceStoreLocalCertificates();
        return SimpleResponse.ok();
    }

    @POST
    @Path("{domain}/reset")
    public Response resetCertificateState(@PathParam("domain") final String domain) {
        var server = ((HttpProxyServer) context.getAttribute("server"));
        server.getDynamicCertificatesManager().setStateOfCertificate(domain, WAITING);
        return SimpleResponse.ok();
    }
}
