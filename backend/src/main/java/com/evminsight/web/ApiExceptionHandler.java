package com.evminsight.web;

import com.evminsight.storage.StorageException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** One consistent error shape; details go to the server log, never to the client (FR-21). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<ErrorResponse> body(HttpStatus s, String code, String msg, Map<String, String> fields) {
        return ResponseEntity.status(s).body(new ErrorResponse(code, msg, fields == null ? Map.of() : fields));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) { return body(e.status(), e.code(), e.getMessage(), e.fieldErrors()); }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<ErrorResponse> storage(StorageException e) {
        log.error("Storage failure [{}]", e.code(), e);
        HttpStatus s = switch (e.code()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_NAME, INVALID_BACKUP -> HttpStatus.BAD_REQUEST;
            case IN_USE, CONFIRMATION_REQUIRED, BACKUP_EXISTS, UNSUPPORTED_VERSION -> HttpStatus.CONFLICT;
            case CORRUPT, IO_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return body(s, "STORAGE_" + e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is missing or malformed (dates must be YYYY-MM-DD, amounts must be numbers)", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> mismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "A request parameter has an invalid value", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This operation is not supported for this resource", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> media(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request body as application/json", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected failure", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", null);
    }
}
