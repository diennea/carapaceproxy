/*
 * Diennea S.r.l. - Copyright 2022, all rights reserved
 */
package org.carapaceproxy.api.response;

import io.netty.handler.codec.http.HttpResponseStatus;
import javax.ws.rs.core.Response;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Form validation REST response
 *
 * @author paolo.venturi
 */
@Getter
@NoArgsConstructor
public class FormValidationResponse extends SimpleResponse {

    public static final String ERROR_FIELD_REQUIRED = "Value required";
    public static final String ERROR_FIELD_INVALID = "Value invalid";
    public static final String ERROR_FIELD_DUPLICATED = "Value already used";

    private String field;

    private FormValidationResponse(String field, String message) {
        super (message);
        this.field = field;
    }

    public static Response fieldRequired(String field) {
        return fieldError(field, ERROR_FIELD_REQUIRED);
    }

    public static Response fieldInvalid(String field) {
        return fieldError(field, ERROR_FIELD_INVALID);
    }

    public static Response fieldError(String field, String message) {
        return Response.status(HttpResponseStatus.UNPROCESSABLE_ENTITY.code())
                .entity(new FormValidationResponse(field, message))
                .build();
    }

    public static Response fieldConflict(String field) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new FormValidationResponse(field, ERROR_FIELD_DUPLICATED))
                .build();
    }

}
