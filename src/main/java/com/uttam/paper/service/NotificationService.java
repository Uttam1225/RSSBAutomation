package com.uttam.paper.service;

import com.uttam.paper.model.NotificationData;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
 * - Storing the latest NotificationData in memory
 */
@Slf4j
@Service
public class NotificationService {

    // In-memory store — holds the most recently uploaded notification
    private volatile NotificationData currentNotification;

    /**
     * Processes an uploaded PDF file:
     * 1. Extracts raw text via PDFBox
     * 2. Parses it into NotificationData
     * 3. Stores it in memory
     *
     * @param file the uploaded PDF MultipartFile
     * @return the parsed NotificationData
     * @throws IOException if PDF reading fails
     */
    public NotificationData processUpload(MultipartFile file) throws IOException {
        log.info("Processing uploaded PDF: {}", file.getOriginalFilename());

        String rawText = extractTextFromPdf(file);
        log.debug("Extracted PDF text ({} chars)", rawText.length());

        NotificationData data = parseNotificationData(rawText);
        currentNotification = data;

        log.info("NotificationData stored in memory. Title: {}", data.getTitle());
        return data;
    }

    /**
     * Returns the currently stored NotificationData (if any).
     */
    public Optional<NotificationData> getCurrentNotification() {
        return Optional.ofNullable(currentNotification);
    }

    /**
     * Clears the in-memory notification.
     */
    public void clearNotification() {
        currentNotification = null;
        log.info("In-memory notification cleared.");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Parses raw PDF text into NotificationData using lightweight heuristics.
     * Sections are identified by common keywords found in exam notifications.
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
                // Return first capturing group if present, else full match
                return m.groupCount() > 0 ? m.group(1) : m.group(0);
            }
        } catch (Exception e) {
            log.warn("Regex extraction failed for pattern '{}': {}", regex, e.getMessage());
        }
        return "";
    }

    private List<String> extractKeywords(String text) {
        // Split on whitespace/punctuation, filter short/common words, deduplicate
        return Arrays.stream(text.split("[\\s,;.:\\-–()]+"))
                .map(String::trim)
                .filter(w -> w.length() > 3)
                .map(String::toLowerCase)
                .distinct()
                .limit(30)
                .collect(Collectors.toList());
    }
}
