package dev.brickfolio.listerizer;

import dev.brickfolio.listerizer.service.ValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ValidationException> {

    private static final String INVALID_REQUEST = "invalid_request";

    @Override
    public Response toResponse(ValidationException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(INVALID_REQUEST, ex.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
