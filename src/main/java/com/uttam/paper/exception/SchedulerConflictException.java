package com.uttam.paper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a scheduler job is already running or date already executed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SchedulerConflictException extends RuntimeException {
    public SchedulerConflictException(String message) {
        super(message);
    }
}
