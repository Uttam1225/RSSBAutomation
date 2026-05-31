package com.uttam.paper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when the full paper generation pipeline fails after all retries. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class PaperGenerationException extends RuntimeException {
    public PaperGenerationException(String message) {
        super(message);
    }
    public PaperGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
