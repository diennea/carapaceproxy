package org.carapaceproxy.server.config;

public record HostPort(String host, int port) {

    public HostPort offsetPort(final int offset) {
        final var newPort = this.port + offset;
        if (newPort < 0 || newPort > 65535) {
            throw new IllegalArgumentException("Offset " + offset + " produces out-of-range HTTP port " + newPort);
        }
        return new HostPort(host, newPort);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
