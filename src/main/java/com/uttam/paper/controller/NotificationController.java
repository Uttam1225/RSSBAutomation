package com.uttam.paper.controller;

import com.uttam.paper.dto.ApiResponse;
import com.uttam.paper.model.NotificationData;
import com.uttam.paper.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * REST controller for notification PDF upload and retrieval.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * POST /api/uploadNotification
     *
     * Accepts a PDF file, extracts text using PDFBox,
     * parses it into NotificationData, and stores it in memory.
     *
     * @param file multipart PDF file (form-data field name: "file")
     * @return 200 OK with parsed NotificationData, or 400/500 on failure
     */
    @PostMapping(value = "/uploadNotification", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<NotificationData>> uploadNotification(
            @RequestParam("file") MultipartFile file) {

        // Validate file presence
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("No file provided or file is empty."));
        }

        // Validate content type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase(MediaType.APPLICATION_PDF_VALUE)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid file type. Only PDF files are accepted."));
        }

        try {
            NotificationData data = notificationService.processUpload(file);
            return ResponseEntity.ok(
                    ApiResponse.ok("Notification uploaded and parsed successfully.", data));

        } catch (IOException e) {
            log.error("Failed to process uploaded PDF: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to process PDF: " + e.getMessage()));
        }
    }

    /**
     * GET /api/notification
     * Returns the currently stored NotificationData.
     * Returns 404 if no notification has been uploaded yet.
     */
    @GetMapping("/notification")
    public ResponseEntity<ApiResponse<NotificationData>> getNotification() {
        NotificationData data = notificationService.getCurrentNotification()
                .orElseThrow(com.uttam.paper.exception.NotificationNotFoundException::new);
        return ResponseEntity.ok(ApiResponse.ok("Current notification data.", data));
    }

    /**
     * DELETE /api/notification
     *
     * Clears the in-memory notification.
     */
    @DeleteMapping("/notification")
    public ResponseEntity<ApiResponse<Void>> clearNotification() {
        notificationService.clearNotification();
        return ResponseEntity.ok(ApiResponse.ok("Notification cleared.", null));
    }
}
