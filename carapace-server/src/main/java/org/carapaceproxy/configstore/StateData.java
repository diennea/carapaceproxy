package org.carapaceproxy.configstore;

import org.carapaceproxy.server.certificates.DynamicCertificateState;

/**
 * The class models additional information to store alongside a {@link DynamicCertificateState} for additional infos.
 * @param cycleCount the number of times the certificate do not step to a following status;
 *                   backward steps should not reset this counter
 * @param message a message for the current state; it may be an error message, or some details, or just stay empty
 */
public record StateData(int cycleCount, String message) {
    public static StateData empty() {
        return new StateData(0, "");
    }
}
