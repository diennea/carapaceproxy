package org.carapaceproxy.core;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.ReferenceCountedOpenSslEngine;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.security.cert.Certificate;
import org.carapaceproxy.server.certificates.ocsp.OcspStaplingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ListenersSniHandler extends SniHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ListenersSniHandler.class);

    private final RuntimeServerConfiguration currentConfiguration;
    private final HttpProxyServer parent;

    public ListenersSniHandler(final RuntimeServerConfiguration currentConfiguration, final HttpProxyServer parent, final ListeningChannel listeningChannel) {
        super(listeningChannel);
        this.currentConfiguration = currentConfiguration;
        this.parent = parent;
    }

    @Override
    protected SslHandler newSslHandler(final SslContext context, final ByteBufAllocator allocator) {
        final SslHandler handler = super.newSslHandler(context, allocator);
        if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported()) {
            final Certificate cert = (Certificate) context.attributes()
                    .attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN))
                    .get();
            if (cert == null) {
                LOG.error("Cannot set OCSP response without the certificate");
                return handler;
            }
            if (!(handler.engine() instanceof ReferenceCountedOpenSslEngine engine)) {
                LOG.error("Unexpected OpenSSL Engine used; cannot set OCSP response.");
                return handler;
            }
            try {
                final OcspStaplingManager ocspStaplingManager = parent.getOcspStaplingManager();
                final byte[] response = ocspStaplingManager.getOcspResponseForCertificate(cert);
                engine.setOcspResponse(response);
            } catch (final IOException ex) {
                LOG.error("Error setting OCSP response.", ex);
            }
        }
        return handler;
    }
}
