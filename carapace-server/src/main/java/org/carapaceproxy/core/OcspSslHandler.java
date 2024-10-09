package org.carapaceproxy.core;

import io.netty.handler.ssl.ReferenceCountedOpenSslEngine;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.security.cert.Certificate;
import java.util.function.Consumer;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OcspSslHandler implements Consumer<SslHandler> {
    private static final Logger LOG = LoggerFactory.getLogger(OcspSslHandler.class);
    private static final AttributeKey<Certificate> ATTRIBUTE = AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN);

    private final SslContext sslContext;
    private final OcspStaplingManager ocspStaplingManager;

    public OcspSslHandler(final SslContext sslContext, final OcspStaplingManager ocspStaplingManager1) {
        this.sslContext = sslContext;
        this.ocspStaplingManager = ocspStaplingManager1;
    }

    @Override
    public void accept(final SslHandler sslHandler) {
        final var cert = sslContext.attributes().attr(ATTRIBUTE).get();
        if (cert == null) {
            LOG.error("Cannot set OCSP response without the certificate");
            return;
        }
        try {
            final var engine = (ReferenceCountedOpenSslEngine) sslHandler.engine();
            engine.setOcspResponse(ocspStaplingManager.getOcspResponseForCertificate(cert));
        } catch (IOException ex) {
            LOG.error("Error setting OCSP response.", ex);
        }
    }
}
