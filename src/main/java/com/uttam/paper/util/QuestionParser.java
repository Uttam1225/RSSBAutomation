package com.uttam.paper.util;

import com.uttam.paper.model.Category;
import com.uttam.paper.model.Language;
import com.uttam.paper.model.Question;
import com.uttam.paper.model.QuestionPaper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw Gemini API text response into a list of {@link QuestionPaper} objects,
 * including the answer key for every set.
 *
 * <p>Expected format (produced by {@link PromptBuilder}):
 * <pre>
 *   === SET 1 ===
 *   [JUNIOR LEVEL]
 *   Q1.
 *   [EN] Question in English
 *   (a) ...  (b) ...  (c) ...  (d) ...
 *   [HIN] प्रश्न हिंदी में
 *   (a) ...  (b) ...  (c) ...  (d) ...
 *   Correct Answer: (a)
 *   ...
 *   [SENIOR LEVEL]
 *   ...
 *   === SET 2 ===  ...  === SET 4 ===
 *   ...
 *   === ANSWER KEY ===
 *   Set 1: Q1-(a), Q2-(b), Q3-(c), ...
 *   Set 2: Q1-(d), Q2-(a), ...
 *   Set 3: ...
 *   Set 4: ...
 * </pre>
 */
@Slf4j
@Component
public class QuestionParser {

    // Matches === SET N === headers
    private static final Pattern SET_HEADER =
            Pattern.compile("===\\s*SET\\s*(\\d+)\\s*===", Pattern.CASE_INSENSITIVE);

    // Matches [JUNIOR LEVEL] or [SENIOR LEVEL]
    private static final Pattern LEVEL_MARKER =
            Pattern.compile("\\[(JUNIOR|SENIOR)\\s+LEVEL]", Pattern.CASE_INSENSITIVE);

    // Matches Q<n>. or Q<n>)
    private static final Pattern QUESTION_NUM =
            Pattern.compile("^Q(\\d+)[.)\\s]", Pattern.CASE_INSENSITIVE);

    // Matches [EN] or [HIN] language tags
    private static final Pattern LANG_TAG =
            Pattern.compile("^\\[(EN|HIN)]\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Marks the start of the answer key section
    private static final Pattern ANSWER_KEY_HEADER =
            Pattern.compile("===\\s*ANSWER\\s*KEY\\s*===", Pattern.CASE_INSENSITIVE);

    // Matches answer key row:  Set 1: Q1-(a), Q2-(b), ...
    private static final Pattern ANSWER_KEY_ROW =
            Pattern.compile("^Set\\s*(\\d+)\\s*:", Pattern.CASE_INSENSITIVE);

    // Matches individual answer tokens inside a row:  Q12-(b)
    private static final Pattern ANSWER_TOKEN =
            Pattern.compile("Q(\\d+)-\\(([a-dA-D])\\)", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses {@code rawText} from Gemini into structured {@link QuestionPaper} objects,
     * each containing its questions and answer key.
     *
     * @param rawText raw response string from Gemini
     * @return list of parsed QuestionPaper (one per set); empty list if parsing fails
     */
    public List<QuestionPaper> parse(String rawText) {
        List<QuestionPaper> papers = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            log.warn("QuestionParser received empty response — returning empty list.");
            return papers;
        }

        String[] lines = rawText.split("\\r?\\n");

        QuestionPaper currentPaper    = null;
        Category      currentCategory = Category.JUNIOR;
        StringBuilder enBuffer        = new StringBuilder();
        StringBuilder hinBuffer       = new StringBuilder();
        boolean       inQuestion      = false;
        boolean       inAnswerKey     = false;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isBlank()) continue;

            // ── Switch to answer key section ─────────────────────────────────
            if (ANSWER_KEY_HEADER.matcher(line).find()) {
                flushQuestion(currentPaper, enBuffer, hinBuffer, currentCategory);
                enBuffer.setLength(0);
                hinBuffer.setLength(0);
                inQuestion  = false;
                inAnswerKey = true;
                log.debug("Entering ANSWER KEY section.");
                continue;
            }

            // ── Answer key rows ───────────────────────────────────────────────
            if (inAnswerKey) {
                parseAnswerKeyRow(line, papers);
                continue;
            }

            // ── SET header ────────────────────────────────────────────────────
            Matcher setMatcher = SET_HEADER.matcher(line);
            if (setMatcher.find()) {
                flushQuestion(currentPaper, enBuffer, hinBuffer, currentCategory);
                enBuffer.setLength(0);
                hinBuffer.setLength(0);
                inQuestion = false;

                int setNumber = Integer.parseInt(setMatcher.group(1));
                currentPaper = QuestionPaper.builder()
                        .setNumber(setNumber)
                        .category(Category.JUNIOR)
                        .build();
                papers.add(currentPaper);
                currentCategory = Category.JUNIOR;
                log.debug("Started SET {}", setNumber);
                continue;
            }

            if (currentPaper == null) continue;

            // ── Level markers ─────────────────────────────────────────────────
            Matcher levelMatcher = LEVEL_MARKER.matcher(line);
            if (levelMatcher.find()) {
                flushQuestion(currentPaper, enBuffer, hinBuffer, currentCategory);
                enBuffer.setLength(0);
                hinBuffer.setLength(0);
                inQuestion      = false;
                currentCategory = levelMatcher.group(1).equalsIgnoreCase("SENIOR")
                        ? Category.SENIOR : Category.JUNIOR;
                log.debug("Level switched to {}", currentCategory);
                continue;
            }

            // ── Question number line ──────────────────────────────────────────
            Matcher qNumMatcher = QUESTION_NUM.matcher(line);
            if (qNumMatcher.find()) {
                flushQuestion(currentPaper, enBuffer, hinBuffer, currentCategory);
                enBuffer.setLength(0);
                hinBuffer.setLength(0);
                inQuestion = true;

                // Handle "Q1. [EN] text" all on same line — capture the EN portion
                String remainder = line.substring(qNumMatcher.end()).trim();
                if (!remainder.isEmpty()) {
                    Matcher langInline = LANG_TAG.matcher(remainder);
                    if (langInline.find()) {
                        String lang = langInline.group(1).toUpperCase();
                        String content = langInline.group(2).trim();
                        if ("EN".equals(lang)) enBuffer.append(content).append("\n");
                        else                   hinBuffer.append(content).append("\n");
                    } else {
                        // No tag — treat remainder as English content
                        enBuffer.append(remainder).append("\n");
                    }
                }
                continue;
            }

            if (!inQuestion) continue;

            // ── Language-tagged content [EN] / [HIN] ─────────────────────────
            Matcher langMatcher = LANG_TAG.matcher(line);
            if (langMatcher.find()) {
                String lang    = langMatcher.group(1).toUpperCase();
                String content = langMatcher.group(2).trim();
                if ("EN".equals(lang)) enBuffer.append(content).append("\n");
                else                   hinBuffer.append(content).append("\n");
                continue;
            }

            // ── Skip inline correct-answer lines ─────────────────────────────
            if (line.toLowerCase().startsWith("correct answer")) continue;

            // ── Continuation lines ────────────────────────────────────────────
            if (enBuffer.length() > 0 && hinBuffer.length() == 0) {
                enBuffer.append(line).append("\n");
            } else if (hinBuffer.length() > 0) {
                hinBuffer.append(line).append("\n");
            }
        }

        // Flush any trailing question
        flushQuestion(currentPaper, enBuffer, hinBuffer, currentCategory);

        int totalQ  = papers.stream().mapToInt(p -> p.getQuestions().size()).sum();
        int totalAK = papers.stream().mapToInt(p -> p.getAnswerKey().size()).sum();
        log.info("Parsed {} set(s) | {} questions | {} answer-key entries.", papers.size(), totalQ, totalAK);

        return papers;
    }

    /**
     * Extracts all question text strings from a list of papers (used for history/uniqueness checks).
     */
    public List<String> extractAllQuestionTexts(List<QuestionPaper> papers) {
        List<String> texts = new ArrayList<>();
        for (QuestionPaper paper : papers) {
            for (Question q : paper.getQuestions()) {
                if (q.getText() != null && !q.getText().isBlank()) {
                    texts.add(q.getText());
                }
            }
        }
        return texts;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses one answer key row and populates the matching paper's answerKey map.
     * Example row:  Set 2: Q1-(a), Q2-(b), Q3-(c), ...
     */
    private void parseAnswerKeyRow(String line, List<QuestionPaper> papers) {
        Matcher rowMatcher = ANSWER_KEY_ROW.matcher(line);
        if (!rowMatcher.find()) return;

        int setNumber = Integer.parseInt(rowMatcher.group(1));
        QuestionPaper target = papers.stream()
                .filter(p -> p.getSetNumber() == setNumber)
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("Answer key references SET {} but no such paper was parsed.", setNumber);
            return;
        }

        Matcher tokenMatcher = ANSWER_TOKEN.matcher(line);
        int count = 0;
        while (tokenMatcher.find()) {
            int    qNum   = Integer.parseInt(tokenMatcher.group(1));
            String answer = tokenMatcher.group(2).toLowerCase();
            target.getAnswerKey().put(qNum, answer);
            count++;
        }
        log.debug("Set {}: loaded {} answer-key entries.", setNumber, count);
    }

    private void flushQuestion(QuestionPaper paper,
                                StringBuilder enBuf,
                                StringBuilder hinBuf,
                                Category category) {
        if (paper == null) return;

        String enText  = enBuf.toString().trim();
        String hinText = hinBuf.toString().trim();

        if (!enText.isBlank()) {
            paper.getQuestions().add(Question.builder()
                    .text(enText)
                    .language(Language.EN)
                    .build());
        }
        if (!hinText.isBlank()) {
            paper.getQuestions().add(Question.builder()
                    .text(hinText)
                    .language(Language.HIN)
                    .build());
        }
    }
}
