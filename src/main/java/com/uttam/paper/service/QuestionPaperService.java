package com.uttam.paper.service;

import com.uttam.paper.exception.NotificationNotFoundException;
import com.uttam.paper.exception.PaperGenerationException;
import com.uttam.paper.model.NotificationData;
import com.uttam.paper.model.QuestionPaper;
import com.uttam.paper.util.PromptBuilder;
import com.uttam.paper.util.QuestionParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates the end-to-end daily question paper generation pipeline:
 *
 * <pre>
 *  1. Load NotificationData  (throws NotificationNotFoundException if absent)
 *  2. Build prompt with UUID seed
 *  3. Call Gemini API
 *  4. Parse raw response → List&lt;QuestionPaper&gt;
 *  5. Atomically validate uniqueness + save (saveIfAllUnique)
 *     └─ duplicate → retry with new seed (up to MAX_RETRIES)
 *  6. Generate PDFs for each set
 *  7. Return List&lt;QuestionPaper&gt;
 *  Throws PaperGenerationException if all retries are exhausted.
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionPaperService {

    private static final int MAX_RETRIES = 3;

    private final NotificationService    notificationService;
    private final PromptBuilder          promptBuilder;
    private final GeminiService          geminiService;
    private final QuestionParser         questionParser;
    private final QuestionHistoryService questionHistoryService;
    private final PdfService             pdfService;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates daily question papers — calls Gemini once per set (4 calls total)
     * to avoid response truncation and ensure full 100-question sets.
     *
     * @return list of 4 {@link QuestionPaper} sets
     * @throws NotificationNotFoundException if no PDF has been uploaded
     * @throws PaperGenerationException      if all retry attempts fail
     */
    public List<QuestionPaper> generateDailyPapers() {
        log.info("=== Starting daily paper generation (1 API call per set) ===");

        // Step 1 — Load NotificationData (throws if absent)
        NotificationData notification = notificationService.getCurrentNotification()
                .orElseThrow(NotificationNotFoundException::new);
        log.info("NotificationData loaded: {}", notification.getTitle());

        List<QuestionPaper> allPapers = new ArrayList<>();

        // Steps 2–4 — Generate each set separately, save PDF immediately
        for (int setNumber = 1; setNumber <= 4; setNumber++) {
            log.info("=== Generating Set {} ===", setNumber);
            QuestionPaper paper = generateSingleSet(notification, setNumber);
            allPapers.add(paper);

            // Save PDF immediately so it's not lost if a later set fails
            try {
                Path pdfPath = pdfService.generatePdf(paper);
                log.info("Set {} PDF saved immediately: {}", setNumber, pdfPath.getFileName());
            } catch (IOException e) {
                log.error("Set {} PDF save failed (non-fatal): {}", setNumber, e.getMessage(), e);
            }
        }

        // Step 5 — Save questions to history (uniqueness check)
        List<String> questionTexts = questionParser.extractAllQuestionTexts(allPapers);
        log.info("Total questions across all sets: {}", questionTexts.size());

        boolean saved = questionHistoryService.saveIfAllUnique(questionTexts);
        if (!saved) {
            // PDFs are already saved — just warn, do not throw
            log.warn("Some questions overlap with history. PDFs already saved. History not updated.");
        }

        log.info("=== Generation complete: {} sets, {} questions ===",
                allPapers.size(), questionTexts.size());
        return allPapers;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a single set by calling Gemini with retries.
     * Falls back to an empty paper (with setNumber only) after MAX_RETRIES exhausted.
     */
    private QuestionPaper generateSingleSet(NotificationData notification, int setNumber) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.info("Set {} — attempt {}/{}", setNumber, attempt, MAX_RETRIES);

            String prompt      = promptBuilder.buildForSet(notification, setNumber);
            String rawResponse = geminiService.generateQuestions(prompt);

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("Set {} attempt {} — Gemini returned empty response.", setNumber, attempt);
                continue;
            }

            log.info("Set {} attempt {} — response length: {} chars", setNumber, attempt, rawResponse.length());

            List<QuestionPaper> parsed = questionParser.parse(rawResponse);
            if (!parsed.isEmpty()) {
                QuestionPaper paper = parsed.get(0);
                // Ensure correct set number in case Gemini used a different one
                paper.setSetNumber(setNumber);
                log.info("Set {} parsed successfully: {} questions, {} AK entries",
                        setNumber, paper.getQuestions().size(), paper.getAnswerKey().size());
                return paper;
            }

            log.warn("Set {} attempt {} — parser returned no papers.", setNumber, attempt);
        }

        log.error("Set {} — all {} attempts failed. Returning empty paper.", setNumber, MAX_RETRIES);
        throw new PaperGenerationException(
                "Set " + setNumber + " failed after " + MAX_RETRIES + " attempts.");
    }

    private List<QuestionPaper> attemptGeneration(NotificationData notification, int attempt) {
        String prompt = promptBuilder.build(notification);
        log.debug("Attempt {} prompt length: {} chars", attempt, prompt.length());

        String rawResponse = geminiService.generateQuestions(prompt);

        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("Attempt {} — Gemini returned an empty response.", attempt);
            return Collections.emptyList();
        }

        List<QuestionPaper> papers = questionParser.parse(rawResponse);
        log.info("Attempt {} — parsed {} set(s).", attempt, papers.size());
        return papers;
    }

    private void generatePdfs(List<QuestionPaper> papers) {
        for (QuestionPaper paper : papers) {
            try {
                Path pdfPath = pdfService.generatePdf(paper);
                log.info("PDF generated: {}", pdfPath.getFileName());
            } catch (IOException e) {
                // PDF failure is non-fatal — questions are already saved
                log.error("PDF generation failed for Set {}: {}", paper.getSetNumber(), e.getMessage(), e);
            }
        }
    }
}

