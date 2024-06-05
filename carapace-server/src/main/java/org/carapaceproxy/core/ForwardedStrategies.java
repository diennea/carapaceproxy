package org.carapaceproxy.core;

import com.google.common.net.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Set;
import org.apache.commons.net.util.SubnetUtils;
import reactor.netty.http.server.ConnectionInfo;

public final class ForwardedStrategies {
    private ForwardedStrategies() {
        throw new AssertionError();
    }

    /**
     * Always drop the {@link HttpHeaders#X_FORWARDED_FOR} header.
     *
     * @return the strategy
     */
    public static ForwardedStrategy drop() {
        return StaticStrategies.DROP;
    }

    /**
     * Act like {@link #preserve()} if host address is one of the trusted ones, else act like {@link #rewrite()}.
     *
     * @return the strategy
     */
    public static ForwardedStrategy ifTrusted(final Set<String> trustedIps) {
        return new IfTrusted(trustedIps);
    }

    /**
     * Always preserve the {@link HttpHeaders#X_FORWARDED_FOR} header.
     *
     * @return the strategy
     */
    public static ForwardedStrategy preserve() {
        return StaticStrategies.PRESERVE;
    }

    /**
     * Always rewrite the {@link HttpHeaders#X_FORWARDED_FOR} header with the current IP.
     *
     * @return the strategy
     */
    public static ForwardedStrategy rewrite() {
        return StaticStrategies.REWRITE;
    }

    enum StaticStrategies implements ForwardedStrategy {
        DROP {
            @Override
            public ConnectionInfo apply(final ConnectionInfo connectionInfo, final HttpRequest request) {
                request.headers().remove(HttpHeaders.X_FORWARDED_FOR);
                return connectionInfo;
            }
        },

        PRESERVE {
            @Override
            public ConnectionInfo apply(final ConnectionInfo connectionInfo, final HttpRequest httpRequest) {
                return connectionInfo;
            }
        },

        REWRITE {
            @Override
            public ConnectionInfo apply(final ConnectionInfo connectionInfo, final HttpRequest request) {
                request.headers().remove(HttpHeaders.X_FORWARDED_FOR);
                final var remoteAddress = connectionInfo.getRemoteAddress();
                if (remoteAddress != null) {
                    request.headers().add(HttpHeaders.X_FORWARDED_FOR, remoteAddress.getAddress().getHostAddress());
                }
                return connectionInfo;
            }
        }
    }

    record IfTrusted(Set<String> trustedIps) implements ForwardedStrategy {

        static final String NAME = "if-trusted";

        @Override
        public ConnectionInfo apply(final ConnectionInfo connectionInfo, final HttpRequest request) {
            if (validate(connectionInfo)) {
                return preserve().apply(connectionInfo, request);
            }
            return rewrite().apply(connectionInfo, request);
        }

        private boolean validate(final ConnectionInfo connectionInfo) {
            if (trustedIps == null) {
                return false;
            }
            final var address = connectionInfo.getRemoteAddress();
            if (address == null) {
                return false;
            }
            for (final var cidr : trustedIps) {
                final var subnetInfo = new SubnetUtils(cidr).getInfo();
                if (subnetInfo.isInRange(address.getAddress().getHostAddress())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String name() {
            return NAME;
        }
    }
}
