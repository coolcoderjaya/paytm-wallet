package com.paytm.wallet.api;

import com.paytm.wallet.dto.ApiError;
import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.ForbiddenException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> idempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", e.getMessage());
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotAcquireLockException.class})
    public ResponseEntity<ApiError> databaseUnavailable(Exception e) {
        log.atError().setCause(e).addKeyValue("event", "database_unavailable").log("database operation unavailable");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.atError().setCause(e).addKeyValue("event", "unexpected_error").log("unexpected request failure");
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(
                code,
                message,
                MDC.get("correlation_id"),
                Instant.now()
        ));
    }
}
