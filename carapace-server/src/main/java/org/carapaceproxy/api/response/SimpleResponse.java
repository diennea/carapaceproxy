/*
 * Diennea S.r.l. - Copyright 2022, all rights reserved
 */
package org.carapaceproxy.api.response;

import io.netty.handler.codec.http.HttpResponseStatus;
import javax.ws.rs.core.Response;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.carapaceproxy.api.RestoreInProgressFilter;
import org.carapaceproxy.server.config.RestoreInProgressException;

/**
 * Simple REST response
 *
 * @author paolo.venturi
 */
@Getter
@NoArgsConstructor
public class SimpleResponse {

    private String message;

    protected SimpleResponse(String message) {
        this.message = message;
    }

    public static Response ok() {
        return Response.status(Response.Status.OK).build();
    }

    public static Response created() {
        return Response.status(Response.Status.CREATED).build();
    }

    public static Response error(Throwable error) {
        // a write refused by a restore may succeed later
        while (!(error instanceof RestoreInProgressException) && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof RestoreInProgressException) {
            return serviceUnavailable(error);
        }
        return Response.status(HttpResponseStatus.UNPROCESSABLE_ENTITY.code())
                .entity(new SimpleResponse(error.getMessage()))
                .build();
    }

    /**
     * A 503 answer with {@code Retry-After}, for a request that may succeed later.
     *
     * @param error why the request cannot be served now
     * @return the response
     */
    public static Response serviceUnavailable(Throwable error) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", RestoreInProgressFilter.RETRY_AFTER_SECONDS)
                .entity(new SimpleResponse(error.getMessage()))
                .build();
    }

}
