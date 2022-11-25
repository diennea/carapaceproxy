/*
 * Diennea S.r.l. - Copyright 2022, all rights reserved
 */
package org.carapaceproxy.api.response;

import io.netty.handler.codec.http.HttpResponseStatus;
import javax.ws.rs.core.Response;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return Response.status(HttpResponseStatus.UNPROCESSABLE_ENTITY.code())
                .entity(new SimpleResponse(error.getMessage()))
                .build();
    }

}
