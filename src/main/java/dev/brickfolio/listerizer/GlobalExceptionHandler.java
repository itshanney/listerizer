package dev.brickfolio.listerizer;

import dev.brickfolio.listerizer.item.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_REQUEST = "invalid_request";

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(INVALID_REQUEST, ex.getMessage()));
    }

    // Jackson deserialization failures (malformed JSON, invalid create_time format)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex) {
        // Unwrap the root cause for a cleaner message; fall back to a generic message
        // if the cause is not something we want to surface directly.
        Throwable cause = ex.getCause();
        String message = (cause != null && cause.getMessage() != null)
                ? cause.getMessage()
                : "Request body is missing or malformed";
        return ResponseEntity.badRequest().body(new ErrorResponse(INVALID_REQUEST, message));
    }
}
