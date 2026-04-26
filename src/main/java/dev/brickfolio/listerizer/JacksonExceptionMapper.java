package dev.brickfolio.listerizer;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import tools.jackson.core.JacksonException;

@Provider
public class JacksonExceptionMapper implements ExceptionMapper<JacksonException> {

    private static final String INVALID_REQUEST = "invalid_request";

    @Override
    public Response toResponse(JacksonException ex) {
        Throwable cause = ex.getCause();
        String message = (cause != null && cause.getMessage() != null)
                ? cause.getMessage()
                : "Request body is missing or malformed";
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(INVALID_REQUEST, message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
