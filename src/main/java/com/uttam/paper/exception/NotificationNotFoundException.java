package com.uttam.paper.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when no notification has been uploaded yet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException() {
        super("No notification has been uploaded. POST a PDF to /api/uploadNotification first.");
    }
}
