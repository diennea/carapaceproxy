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

import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import org.carapaceproxy.server.HttpProxyServer;
import org.carapaceproxy.server.RuntimeServerConfiguration;
import org.carapaceproxy.server.certiticates.DynamicCertificatesManager;
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
                    certificate.getSslCertificateFile()
            );

            if (certBean.isDynamic()) {
                certBean.setStatus(dynamicCertificateManager.getStateOfCertificate(certBean.getId()).toString());
            }
            res.put(certificateEntry.getKey(), certBean);
        }

        return res;
    }

    @GET
    @Path("{certId}")
    public Map<String, CertificateBean> getCertificateById(@PathParam("certId") String certId) {
        HttpProxyServer server = (HttpProxyServer) context.getAttribute("server");
        RuntimeServerConfiguration conf = server.getCurrentConfiguration();
        Map<String, SSLCertificateConfiguration> certificateList = conf.getCertificates();
        DynamicCertificatesManager dynamicCertificateManager = server.getDynamicCertificateManager();

        Map<String, CertificateBean> res = new HashMap<>();
        if (certificateList.containsKey(certId)) {
            SSLCertificateConfiguration certificate = certificateList.get(certId);
            CertificateBean certBean = new CertificateBean(
                    certificate.getId(),
                    certificate.getHostname(),
                    certificate.isDynamic(),
                    certificate.getSslCertificateFile()
            );

            if (certBean.isDynamic()) {
                certBean.setStatus(dynamicCertificateManager.getStateOfCertificate(certBean.getId()).toString());
            }
            res.put(certId, certBean);
        }

        return res;
    }
}
