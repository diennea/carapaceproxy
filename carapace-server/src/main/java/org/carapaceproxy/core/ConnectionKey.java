package org.carapaceproxy.core;

import reactor.netty.http.HttpProtocol;

/**
 * The class models the key used to identify a connection.
 *
 * @param host            the string to get hostname and port from
 * @param protocolVersion the HTTP protocol version; this is important to avoid mismatches
 */
public record ConnectionKey(String host, HttpProtocol protocolVersion) {
    public ConnectionKey(final EndpointKey key, final String id, final HttpProtocol protocolVersion) {
        this(key.toString() + "_" + id, protocolVersion);
    }
}
