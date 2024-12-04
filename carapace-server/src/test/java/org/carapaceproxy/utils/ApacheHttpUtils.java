package org.carapaceproxy.utils;

import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;

public class ApacheHttpUtils {

    public static CloseableHttpClient createHttpClientWithDisabledSSLValidation() throws Exception {
        return HttpClients.custom()
                .setSSLContext(SSLContextBuilder.create()
                        .loadTrustMaterial((chain, authType) -> true) // Trust all certificates
                        .build())
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE) // Disable hostname verification
                .build();
    }
}
