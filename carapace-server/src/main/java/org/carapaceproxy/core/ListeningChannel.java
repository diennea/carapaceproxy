package org.carapaceproxy.core;

import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreData;
import static org.carapaceproxy.utils.CertificatesUtils.loadKeyStoreFromFile;
import static org.carapaceproxy.utils.CertificatesUtils.readChainFromKeystore;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslCachingX509KeyManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AsyncMapping;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.prometheus.client.Counter;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import lombok.Data;
import org.carapaceproxy.server.config.ConfigurationNotValidException;
import org.carapaceproxy.server.config.HostPort;
import org.carapaceproxy.server.config.NetworkListenerConfiguration;
import org.carapaceproxy.server.config.SSLCertificateConfiguration;
import org.carapaceproxy.utils.PrometheusUtils;
import reactor.netty.DisposableServer;

@Data
public class ListeningChannel implements AsyncMapping<String, SslContext> {

    private static final Logger LOG = Logger.getLogger(ListeningChannel.class.getName());
    private static final Counter TOTAL_REQUESTS_PER_LISTENER_COUNTER = PrometheusUtils.createCounter(
            "listeners", "requests_total", "total requests", "listener"
    ).register();

    private final HostPort hostPort;
    private final NetworkListenerConfiguration config;
    private final Counter.Child totalRequests;
    private final Map<String, SslContext> listenerSslContexts = new HashMap<>();
    private final File basePath;
    private final RuntimeServerConfiguration currentConfiguration;
    private final HttpProxyServer parent;
    private final Map<String, SslContext> sslContexts;
    DisposableServer channel;

    public ListeningChannel(final File basePath, final RuntimeServerConfiguration currentConfiguration, final HttpProxyServer parent, final Map<String, SslContext> sslContexts, HostPort hostPort, NetworkListenerConfiguration config) {
        this.hostPort = hostPort;
        this.config = config;
        this.totalRequests = TOTAL_REQUESTS_PER_LISTENER_COUNTER.labels(hostPort.host() + "_" + hostPort.port());
        this.basePath = basePath;
        this.currentConfiguration = currentConfiguration;
        this.parent = parent;
        this.sslContexts = sslContexts;
    }

    public void incRequests() {
        totalRequests.inc();
    }

    public void clear() {
        this.listenerSslContexts.clear();
    }

    @Override
    public Future<SslContext> map(String sniHostname, Promise<SslContext> promise) {
        try {
            String key = config.getHost() + ":" + hostPort.port() + "+" + sniHostname;
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "resolve SNI mapping {0}, key: {1}", new Object[]{sniHostname, key});
            }
            try {
                SslContext sslContext = listenerSslContexts.get(key);
                if (sslContext != null) {
                    return promise.setSuccess(sslContext);
                }

                sslContext = sslContexts.computeIfAbsent(key, (k) -> {
                    try {
                        SSLCertificateConfiguration choosen = Listeners.chooseCertificate(currentConfiguration, sniHostname, config.getDefaultCertificate());
                        if (choosen == null) {
                            throw new ConfigurationNotValidException("cannot find a certificate for snihostname " + sniHostname
                                                                     + ", with default cert for listener as '" + config.getDefaultCertificate()
                                                                     + "', available " + currentConfiguration.getCertificates().keySet());
                        }
                        return bootSslContext(config, choosen);
                    } catch (ConfigurationNotValidException ex) {
                        throw new RuntimeException(ex);
                    }
                });
                listenerSslContexts.put(key, sslContext);

                return promise.setSuccess(sslContext);
            } catch (RuntimeException err) {
                if (err.getCause() instanceof ConfigurationNotValidException) {
                    throw (ConfigurationNotValidException) err.getCause();
                } else {
                    throw new ConfigurationNotValidException(err);
                }
            }
        } catch (ConfigurationNotValidException err) {
            LOG.log(Level.SEVERE, "Error booting certificate for SNI hostname {0}, on listener {1}", new Object[]{sniHostname, config});
            return promise.setFailure(err);
        }
    }

    private SslContext bootSslContext(NetworkListenerConfiguration listener, SSLCertificateConfiguration certificate) throws ConfigurationNotValidException {
        int port = listener.getPort() + parent.getListenersOffsetPort();
        String sslCiphers = listener.getSslCiphers();

        try {
            // Try to find certificate data on db
            byte[] keystoreContent = parent.getDynamicCertificatesManager().getCertificateForDomain(certificate.getId());
            KeyStore keystore;
            if (keystoreContent != null) {
                LOG.log(Level.FINE, "start SSL with dynamic certificate id {0}, on listener {1}:{2}", new Object[]{certificate.getId(), listener.getHost(), port});
                keystore = loadKeyStoreData(keystoreContent, certificate.getPassword());
            } else {
                if (certificate.isDynamic()) { // fallback to default certificate
                    certificate = currentConfiguration.getCertificates().get(listener.getDefaultCertificate());
                    if (certificate == null) {
                        throw new ConfigurationNotValidException("Unable to boot SSL context for listener " + listener.getHost() + ": no default certificate setup.");
                    }
                }
                LOG.log(Level.FINE, "start SSL with certificate id {0}, on listener {1}:{2} file={3}",
                        new Object[]{certificate.getId(), listener.getHost(), port, certificate.getFile()}
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
            SslContext sslContext = SslContextBuilder
                    .forServer(keyFactory)
                    .enableOcsp(currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported())
                    .trustManager(parent.getTrustStoreManager().getTrustManagerFactory())
                    .sslProvider(SslProvider.OPENSSL)
                    .protocols(listener.getSslProtocols())
                    .ciphers(ciphers).build();

            Certificate[] chain = readChainFromKeystore(keystore);
            if (currentConfiguration.isOcspEnabled() && OpenSsl.isOcspSupported() && chain.length > 0) {
                parent.getOcspStaplingManager().addCertificateForStapling(chain);
                Attribute<Object> attr = sslContext.attributes().attr(AttributeKey.valueOf(Listeners.OCSP_CERTIFICATE_CHAIN));
                attr.set(chain[0]);
            }

            return sslContext;
        } catch (IOException | GeneralSecurityException err) {
            LOG.log(Level.SEVERE, "ERROR booting listener " + err, err);
            throw new ConfigurationNotValidException(err);
        }
    }
}
