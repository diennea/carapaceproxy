package org.carapaceproxy.server.config;

public record HostPort(String host, int port) {
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
