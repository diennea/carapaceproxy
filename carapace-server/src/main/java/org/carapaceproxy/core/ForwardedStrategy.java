package org.carapaceproxy.core;

import static org.carapaceproxy.core.ForwardedStrategies.StaticStrategies;
import com.google.common.net.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Set;
import java.util.function.BiFunction;
import org.carapaceproxy.configstore.ConfigurationStore;
import org.carapaceproxy.core.ForwardedStrategies.IfTrusted;
import reactor.netty.http.server.ConnectionInfo;

public sealed interface ForwardedStrategy extends BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> permits StaticStrategies, IfTrusted {

    /**
     * Choose a strategy to handle {@value HttpHeaders#X_FORWARDED_FOR} header given and optional set of trusted IPs.
     *
     * @param name       the name of the strategy from the {@link ConfigurationStore config}
     * @param trustedIps an optional set of trusted IPs from the {@link ConfigurationStore config}
     * @return the appropriate strategy object
     */
    static ForwardedStrategy of(final String name, final Set<String> trustedIps) {
        if (StaticStrategies.DROP.name().equals(name)) {
            return ForwardedStrategies.drop();
        }
        if (StaticStrategies.PRESERVE.name().equals(name)) {
            return ForwardedStrategies.preserve();
        }
        if (StaticStrategies.REWRITE.name().equals(name)) {
            return ForwardedStrategies.rewrite();
        }
        if (IfTrusted.NAME.equals(name)) {
            return ForwardedStrategies.ifTrusted(trustedIps);
        }
        throw new IllegalArgumentException("Unexpected forwarded strategy: " + name);
    }

    /**
     * Get a name for the strategy.
     *
     * @return the name
     * @see Enum#name()
     */
    String name();
}
