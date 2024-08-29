package org.carapaceproxy.core;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import reactor.netty.tcp.SslProvider;

record ListenerSslProviderBuilder(
        File basePath,
        RuntimeServerConfiguration currentConfiguration,
        HttpProxyServer parent,
        HostPort hostPort,
        NetworkListenerConfiguration config
) implements Consumer<SslProvider.SslContextSpec> {
    private static final Logger LOG = Logger.getLogger(ListenerSslProviderBuilder.class.getName());

    @Override
    public void accept(final SslProvider.SslContextSpec sslContextSpec) {
        final int port = hostPort.port();
        final String sslCiphers = config.getSslCiphers();
        SslContext sslContext;
        try {
            SSLCertificateConfiguration certificate = Listeners.chooseCertificate(currentConfiguration, null, config.getDefaultCertificate());
            Objects.requireNonNull(certificate, "Cannot use default cert for listener as '" + config.getDefaultCertificate() + "', available " + currentConfiguration.getCertificates().keySet());
            // Try to find certificate data on db
            byte[] keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
            KeyStore keystore;
            if (keystoreContent != null) {
                LOG.log(Level.FINE, "start SSL with dynamic certificate id {0}, on listener {1}:{2}", new Object[]{certificate.getId(), config.getHost(), port});
                keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
            } else {
                if (certificate.isDynamic()) { // fallback to default certificate
                    certificate = currentConfiguration.getCertificates().get(config.getDefaultCertificate());
                    if (certificate == null) {
                        throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + config.getHost() + ": no default certificate setup.").unchecked();
                    }
                }
                LOG.log(Level.FINE, "start SSL with certificate id {0}, on listener {1}:{2} file={3}",
                        new Object[]{certificate.getId(), config.getHost(), port, certificate.getFile()}
                );
                keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
            }
            KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
            keyFactory.init(keystore, certificate.getPassword().toCharArray());

            List<String> ciphers = null;
            if (sslCiphers != null && !sslCiphers.isEmpty()) {
                LOG.log(Level.FINE, "required sslCiphers {0}", sslCiphers);
                ciphers = Arrays.asList(sslCiphers.split(","));
            }
            sslContext = SslContextBuilder
                    .forServer(keyFactory)
                    .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                    .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                    .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                    .protocols(config.getSslProtocols())
                    .ciphers(ciphers).build();

            Certificate[] chain = readChainFromKeystore(keystore);
            if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                parent.getOcspStaplingManager().addCertificateForStapling(chain);
                Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                attr.set(chain[0]);
            }
        } catch (IOException | GeneralSecurityException err) {
            LOG.log(Level.SEVERE, "ERROR booting listener " + err, err);
            throw new ConfigurationNotValidException(err).unchecked();
        }
        sslContextSpec.sslContext(sslContext).setSniAsyncMappings((String sniHostname) -> {
            final var promise = ImmediateEventExecutor.INSTANCE.<SslProvider>newPromise();
            return CompletableFuture.supplyAsync(() -> {
                SSLCertificateConfiguration certificate = Listeners.chooseCertificate(currentConfiguration, null, config.getDefaultCertificate());
                if (certificate == null) {
                    return promise.setFailure(new ConfigurationNotValidException(
                            "cannot find a certificate for snihostname " + sniHostname
                            + ", with default cert for listener as '" + config.getDefaultCertificate()
                            + "', available " + currentConfiguration.getCertificates().keySet()));
                }
                final SslContext mappedSslContext;
                try {
                    // Try to find certificate data on db
                    byte[] keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
                    KeyStore keystore;
                    if (keystoreContent != null) {
                        LOG.log(Level.FINE, "start SSL with dynamic certificate id {0}, on listener {1}:{2}", new Object[]{certificate.getId(), config.getHost(), port});
                        keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
                    } else {
                        if (certificate.isDynamic()) { // fallback to default certificate
                            certificate = currentConfiguration.getCertificates().get(config.getDefaultCertificate());
                            if (certificate == null) {
                                return promise.setFailure(new ConfigurationNotValidException("Unable to boot SSL context for listener " + config.getHost() + ": no default certificate setup."));
                            }
                        }
                        LOG.log(Level.FINE, "start SSL with certificate id {0}, on listener {1}:{2} file={3}",
                                new Object[]{certificate.getId(), config.getHost(), port, certificate.getFile()}
                        );
                        keystore = loadKeyStoreFromFile(certificate.getFile(), certificate.getPassword(), basePath);
                    }
                    KeyManagerFactory keyFactory = new OpenSslCachingX509KeyManagerFactory(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()));
                    keyFactory.init(keystore, certificate.getPassword().toCharArray());

                    List<String> ciphers = null;
                    if (sslCiphers != null && !sslCiphers.isEmpty()) {
                        LOG.log(Level.FINE, "required sslCiphers {0}", sslCiphers);
                        ciphers = Arrays.asList(sslCiphers.split(","));
                    }
                    mappedSslContext = SslContextBuilder
                            .forServer(keyFactory)
                            .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                            .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                            .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                            .protocols(config.getSslProtocols())
                            .ciphers(ciphers).build();

                    Certificate[] chain = readChainFromKeystore(keystore);
                    if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                        parent.getOcspStaplingManager().addCertificateForStapling(chain);
                        Attribute<Object> attr = mappedSslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                        attr.set(chain[0]);
                    }
                } catch (IOException | GeneralSecurityException err) {
                    LOG.log(Level.SEVERE, "ERROR booting listener " + err, err);
                    return promise.setFailure(new ConfigurationNotValidException(err));
                }
                return promise.setSuccess(SslProvider.builder().sslContext(mappedSslContext).build());
            });
        });
    }
}
