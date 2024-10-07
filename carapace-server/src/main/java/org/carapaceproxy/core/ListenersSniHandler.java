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
    protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
        final var handler = super.newSslHandler(context, allocator);
        if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported()) {
            final var cert = (Certificate) context.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN)).get();
            if (cert != null) {
                try {
                    final var engine = (ReferenceCountedOpenSslEngine) handler.engine();
                    engine.setOcspResponse(parent.getOcspStaplingManager().getOcspResponseForCertificate(cert));
                } catch (IOException ex) {
                    LOG.error("Error setting OCSP response.", ex);
                }
            } else {
                LOG.error("Cannot set OCSP response without the certificate");
            }
        }
        return handler;
    }
}
