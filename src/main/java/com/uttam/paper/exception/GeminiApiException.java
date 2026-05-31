package com.uttam.paper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown on unrecoverable Gemini API errors (4xx or all retries exhausted). */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class GeminiApiException extends RuntimeException {
    public GeminiApiException(String message) {
        super(message);
    }
    public GeminiApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
