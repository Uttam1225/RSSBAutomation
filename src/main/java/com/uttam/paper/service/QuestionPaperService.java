package com.uttam.paper.service;

import com.uttam.paper.exception.NotificationNotFoundException;
import com.uttam.paper.exception.PaperGenerationException;
import com.uttam.paper.model.Category;
import com.uttam.paper.model.NotificationData;
import com.uttam.paper.model.PaperType;
import com.uttam.paper.model.QuestionPaper;
import com.uttam.paper.util.PromptBuilder;
import com.uttam.paper.util.QuestionParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the end-to-end daily question paper generation pipeline.
 *
 * <p>Generates 4 sets per run:
 * <ul>
 *   <li>Set 1 — Junior / Non-Technical</li>
 *   <li>Set 2 — Junior / Technical</li>
 *   <li>Set 3 — Senior / Non-Technical</li>
 *   <li>Set 4 — Senior / Technical</li>
 * </ul>
 * Each set = 100 bilingual (English + Hindi) MCQ questions.
 * A single-set failure is non-fatal; the run fails only if ALL sets fail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionPaperService {

    private static final int MAX_RETRIES = 3;

    /** Ordered 4-set configuration: {setNumber, role, paperType}. */
    private record SetConfig(int setNumber, Category role, PaperType type) {}

    private static final List<SetConfig> SET_CONFIGS = List.of(
            new SetConfig(1, Category.JUNIOR, PaperType.NON_TECHNICAL),
            new SetConfig(2, Category.JUNIOR, PaperType.TECHNICAL),
            new SetConfig(3, Category.SENIOR, PaperType.NON_TECHNICAL),
            new SetConfig(4, Category.SENIOR, PaperType.TECHNICAL)
    );

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
     * Generates all 4 role × type paper sets for today.
     *
     * @return list of successfully generated {@link QuestionPaper} objects
     * @throws NotificationNotFoundException if no PDF has been uploaded
     * @throws PaperGenerationException      if every set fails
     */
    public List<QuestionPaper> generateDailyPapers() {
        log.info("=== Starting daily paper generation (4 sets: role × type) ===");

        NotificationData notification = notificationService.getCurrentNotification()
                .orElseThrow(NotificationNotFoundException::new);
        log.info("NotificationData loaded: {}", notification.getTitle());

        List<QuestionPaper> allPapers = new ArrayList<>();
        List<Integer>       failedSets = new ArrayList<>();

        for (SetConfig cfg : SET_CONFIGS) {
            log.info("=== Generating Set {} — {} / {} ===",
                    cfg.setNumber(), cfg.role(), cfg.type());
            try {
                QuestionPaper paper = generateSingleSet(notification, cfg);
                allPapers.add(paper);

                try {
                    Path pdfPath = pdfService.generatePdf(paper);
                    log.info("Set {} PDF saved: {}", cfg.setNumber(), pdfPath.getFileName());
                } catch (IOException e) {
                    log.error("Set {} PDF save failed (non-fatal): {}",
                            cfg.setNumber(), e.getMessage(), e);
                }

            } catch (PaperGenerationException e) {
                log.warn("Set {} ({}/{}) failed — skipping: {}",
                        cfg.setNumber(), cfg.role(), cfg.type(), e.getMessage());
                failedSets.add(cfg.setNumber());
            }
        }

        if (allPapers.isEmpty()) {
            throw new PaperGenerationException(
                    "All 4 sets failed to generate. Failed sets: " + failedSets);
        }

        if (!failedSets.isEmpty()) {
            log.warn("Partial generation — failed sets: {}. Successful: {}/4",
                    failedSets, allPapers.size());
        }

        List<String> questionTexts = questionParser.extractAllQuestionTexts(allPapers);
        log.info("Total questions across {} set(s): {}", allPapers.size(), questionTexts.size());

        boolean saved = questionHistoryService.saveIfAllUnique(questionTexts);
        if (!saved) {
            log.warn("Some questions overlap with history. PDFs already saved. History not updated.");
        }

        log.info("=== Generation complete: {}/4 sets, {} questions ===",
                allPapers.size(), questionTexts.size());
        return allPapers;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private QuestionPaper generateSingleSet(NotificationData notification, SetConfig cfg) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.info("Set {} ({}/{}) — attempt {}/{}",
                    cfg.setNumber(), cfg.role(), cfg.type(), attempt, MAX_RETRIES);

            String prompt      = promptBuilder.buildForSet(notification, cfg.setNumber(), cfg.role(), cfg.type());
            String rawResponse = geminiService.generateQuestions(prompt);

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("Set {} attempt {} — Gemini returned empty response.", cfg.setNumber(), attempt);
                continue;
            }

            log.info("Set {} attempt {} — response length: {} chars",
                    cfg.setNumber(), attempt, rawResponse.length());

            List<QuestionPaper> parsed = questionParser.parse(rawResponse);
            if (!parsed.isEmpty()) {
                QuestionPaper paper = parsed.get(0);
                paper.setSetNumber(cfg.setNumber());
                paper.setCategory(cfg.role());
                paper.setPaperType(cfg.type());
                log.info("Set {} parsed: {} questions, {} AK entries",
                        cfg.setNumber(), paper.getQuestions().size(), paper.getAnswerKey().size());
                return paper;
            }

            log.warn("Set {} attempt {} — parser returned no papers.", cfg.setNumber(), attempt);
        }

        throw new PaperGenerationException(
                "Set " + cfg.setNumber() + " (" + cfg.role() + "/" + cfg.type()
                        + ") failed after " + MAX_RETRIES + " attempts.");
    }
}

