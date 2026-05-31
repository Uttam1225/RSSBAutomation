package com.uttam.paper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when the question history file cannot be read or written. */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class QuestionHistoryException extends RuntimeException {
    public QuestionHistoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
