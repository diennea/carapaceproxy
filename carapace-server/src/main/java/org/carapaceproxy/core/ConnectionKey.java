package org.carapaceproxy.core;

import org.carapaceproxy.client.EndpointKey;
import reactor.netty.http.HttpProtocol;

public record ConnectionKey(String host, HttpProtocol protocolVersion) {
    public ConnectionKey(final EndpointKey key, final String id, final HttpProtocol protocolVersion) {
        this(key.getHostPort() + "_" + id, protocolVersion);
    }
}
