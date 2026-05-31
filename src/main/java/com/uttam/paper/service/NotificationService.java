package com.uttam.paper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.model.NotificationData;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Singleton service responsible for:
 * - Extracting text from an uploaded PDF using Apache PDFBox
 * - Parsing the extracted text into a {@link NotificationData} object
 * - Persisting the latest NotificationData to JSON (survives restarts)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String NOTIFICATION_FILE = "notification-data.json";

    private final ConfigLoader configLoader;
    private final ObjectMapper objectMapper;

    // In-memory store — holds the most recently uploaded notification
    private volatile NotificationData currentNotification;

    /** Load persisted notification on startup so re-upload is not required after restart. */
    @PostConstruct
    public void loadPersistedNotification() {
        Path file = notificationFilePath();
        if (Files.exists(file)) {
            try {
                currentNotification = objectMapper.readValue(file.toFile(), NotificationData.class);
                log.info("Notification loaded from disk: {} ({})",
                        currentNotification.getTitle(), file);
            } catch (Exception e) {
                log.warn("Could not load persisted notification from {}: {}", file, e.getMessage());
            }
        } else {
            log.info("No persisted notification found at {}. Upload a PDF to /api/uploadNotification.", file);
        }
    }

    /**
     * Processes an uploaded PDF file:
     * 1. Extracts raw text via PDFBox
     * 2. Parses it into NotificationData
     * 3. Persists it to JSON and stores in memory
     *
     * @param file the uploaded PDF MultipartFile
     * @return the parsed NotificationData
     * @throws IOException if PDF reading or JSON write fails
     */
    public NotificationData processUpload(MultipartFile file) throws IOException {
        log.info("Processing uploaded PDF: {}", file.getOriginalFilename());

        String rawText = extractTextFromPdf(file);
        log.debug("Extracted PDF text ({} chars)", rawText.length());

        NotificationData data = parseNotificationData(rawText);
        currentNotification = data;

        // Persist so it survives restarts
        persist(data);

        log.info("NotificationData stored. Title: {}", data.getTitle());
        return data;
    }

    /**
     * Returns the currently stored NotificationData (if any).
     */
    public Optional<NotificationData> getCurrentNotification() {
        return Optional.ofNullable(currentNotification);
    }

    /**
     * Clears both in-memory and persisted notification.
     */
    public void clearNotification() {
        currentNotification = null;
        try {
            Files.deleteIfExists(notificationFilePath());
        } catch (IOException e) {
            log.warn("Could not delete persisted notification file: {}", e.getMessage());
        }
        log.info("Notification cleared.");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void persist(NotificationData data) {
        try {
            Path dir = Paths.get(configLoader.getOutputDir());
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(notificationFilePath().toFile(), data);
            log.info("Notification persisted to {}", notificationFilePath());
        } catch (IOException e) {
            log.warn("Could not persist notification to disk: {}", e.getMessage());
        }
    }

    private Path notificationFilePath() {
        return Paths.get(configLoader.getOutputDir()).resolve(NOTIFICATION_FILE);
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Parses raw PDF text into NotificationData using lightweight heuristics.
     */
    private NotificationData parseNotificationData(String text) {
        String title      = extractSection(text, "(?i)(post|advertisement|notification|exam)[^\n]*");
        String syllabus   = extractSection(text, "(?is)syllabus[:\\s]*(.*?)(exam pattern|pattern|schedule|$)");
        String examPattern= extractSection(text, "(?is)(exam pattern|paper pattern)[:\\s]*(.*?)(syllabus|schedule|$)");
        List<String> keywords = extractKeywords(syllabus + " " + examPattern);

        return NotificationData.builder()
                .title(title.isBlank() ? "Unknown Notification" : title.trim())
                .syllabus(syllabus.trim())
                .examPattern(examPattern.trim())
                .keywords(keywords)
                .build();
    }

    private String extractSection(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                return m.groupCount() > 0 ? m.group(1) : m.group(0);
            }
        } catch (Exception e) {
            log.warn("Regex extraction failed for pattern '{}': {}", regex, e.getMessage());
        }
        return "";
    }

    private List<String> extractKeywords(String text) {
        return Arrays.stream(text.split("[\\s,;.:\\-–()]+"))
                .map(String::trim)
                .filter(w -> w.length() > 3)
                .map(String::toLowerCase)
                .distinct()
                .limit(30)
                .collect(Collectors.toList());
    }
}

