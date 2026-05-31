package com.uttam.paper.exception;

import com.uttam.paper.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

/**
 * Centralised exception → HTTP response mapping.
 *
 * <pre>
 *  NotificationNotFoundException  → 404 Not Found
 *  SchedulerConflictException     → 409 Conflict
 *  GeminiApiException             → 502 Bad Gateway
 *  PaperGenerationException       → 502 Bad Gateway
 *  QuestionHistoryException       → 500 Internal Server Error
 *  MaxUploadSizeExceededException → 413 Payload Too Large
 *  IllegalArgumentException       → 400 Bad Request
 *  IOException                    → 500 Internal Server Error
 *  Exception (fallback)           → 500 Internal Server Error
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotificationNotFound(
            NotificationNotFoundException ex) {
        log.warn("404 NotificationNotFound: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(SchedulerConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleSchedulerConflict(
            SchedulerConflictException ex) {
        log.warn("409 SchedulerConflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({GeminiApiException.class, PaperGenerationException.class})
    public ResponseEntity<ApiResponse<Void>> handleGatewayErrors(RuntimeException ex) {
        log.error("502 Gateway error [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(QuestionHistoryException.class)
    public ResponseEntity<ApiResponse<Void>> handleHistoryError(QuestionHistoryException ex) {
        log.error("500 QuestionHistoryException: {}", ex.getMessage(), ex.getCause());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("History file error: " + ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleFileTooLarge(
            MaxUploadSizeExceededException ex) {
        log.warn("413 File too large: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("Uploaded file exceeds maximum allowed size (20 MB)."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        log.warn("400 IllegalArgument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIO(IOException ex) {
        log.error("500 IOException: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("I/O error: " + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception ex) {
        log.error("500 Unhandled exception [{}]: {}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please check the server logs."));
    }
}
