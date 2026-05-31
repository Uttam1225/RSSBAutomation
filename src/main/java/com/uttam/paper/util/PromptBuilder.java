package com.uttam.paper.util;

import com.uttam.paper.model.NotificationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Builds a deterministic-but-unique prompt for the Gemini API.
 *
 * <p>Each call produces a prompt that:
 * <ul>
 *   <li>Instructs Gemini to generate questions in both English and Hindi (bilingual)</li>
 *   <li>Follows the 2022 RSSB exam format (100 MCQs, 4 options each)</li>
 *   <li>Produces 4 distinct sets (Set 1–4)</li>
 *   <li>Covers both Junior and Senior level difficulty</li>
 *   <li>Embeds today's date + a UUID seed to guarantee uniqueness across runs</li>
 *   <li>Explicitly instructs the model not to repeat previous questions</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final int SETS          = 4;
    private static final int QUESTIONS_PER_SET = 100;
    private static final int OPTIONS_PER_Q  = 4;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a prompt for a SINGLE set (used when calling Gemini once per set).
     *
     * @param data      parsed notification
     * @param setNumber which set to generate (1–4)
     * @return ready-to-send prompt string
     */
    public String buildForSet(NotificationData data, int setNumber) {
        String date = LocalDate.now().format(DATE_FMT);
        String seed = UUID.randomUUID().toString();

        log.info("Building prompt | date={} | seed={} | set={} | title={}", date, seed, setNumber, data.getTitle());

        return new StringBuilder()
                .append(uniquenessHeader(date, seed, setNumber))
                .append(bilingualInstruction())
                .append(examContext(data))
                .append(examFormatInstruction())
                .append(singleSetInstruction(setNumber))
                .append(levelInstruction())
                .append(keywordInstruction(data.getKeywords()))
                .append(singleSetOutputFormat(setNumber))
                .append(singleSetUniquenessFooter())
                .toString();
    }

    /**
     * Builds a prompt for all 4 sets at once (kept for compatibility).
     *
     * @param data parsed notification
     * @return ready-to-send prompt string
     */
    public String build(NotificationData data) {
        String date = LocalDate.now().format(DATE_FMT);
        String seed = UUID.randomUUID().toString();

        log.info("Building prompt | date={} | seed={} | title={}", date, seed, data.getTitle());

        return new StringBuilder()
                .append(uniquenessHeader(date, seed, 0))
                .append(bilingualInstruction())
                .append(examContext(data))
                .append(examFormatInstruction())
                .append(setInstruction())
                .append(levelInstruction())
                .append(keywordInstruction(data.getKeywords()))
                .append(outputFormatInstruction())
                .append(uniquenessFooter())
                .toString();
    }

    // -------------------------------------------------------------------------
    // Prompt sections
    // -------------------------------------------------------------------------

    /**
     * Embeds date + UUID so every prompt is treated as new by the model,
     * preventing cached / repeated responses.
     */
    private String uniquenessHeader(String date, String seed, int setNumber) {
        String setInfo = setNumber > 0 ? "Set Number      : " + setNumber + "\n" : "";
        return """
                === SESSION METADATA (do not include in output) ===
                Generation Date : %s
                Unique Seed     : %s
                %s=====================================================

                """.formatted(date, seed, setInfo);
    }

    /** Instructs the model to produce every question in both English and Hindi. */
    private String bilingualInstruction() {
        return """
                BILINGUAL FORMAT — MANDATORY — Follow this EXACTLY for every single question:

                Q<number>.
                [EN] <Complete question in English>
                (a) <Option A in English>  (b) <Option B in English>  (c) <Option C in English>  (d) <Option D in English>
                [HIN] <Same question in Hindi>
                (a) <Option A in Hindi>  (b) <Option B in Hindi>  (c) <Option C in Hindi>  (d) <Option D in Hindi>
                Correct Answer: (<letter>)

                CRITICAL RULES:
                - Q<number>. must be on its OWN line. Do NOT write "Q1. [EN]..." on the same line.
                - [EN] must ALWAYS appear on a NEW line by itself followed by the English text.
                - [HIN] must ALWAYS appear on a NEW line by itself followed by the Hindi text.
                - Every question MUST have BOTH [EN] and [HIN] blocks. Never skip either language.

                """;
    }

    /** Injects notification title, syllabus, and exam pattern as context. */
    private String examContext(NotificationData data) {
        return """
                EXAM CONTEXT:
                Notification Title : %s
                Syllabus           : %s
                Exam Pattern       : %s

                """.formatted(
                nullSafe(data.getTitle()),
                nullSafe(data.getSyllabus()),
                nullSafe(data.getExamPattern()));
    }

    /** Specifies the 2022 RSSB exam format rules. */
    private String examFormatInstruction() {
        return """
                EXAM FORMAT (RSSB 2022 Standard):
                - Total questions per set : %d MCQs
                - Options per question    : %d (a, b, c, d)
                - Each question carries   : 1 mark
                - Negative marking        : 1/3 mark deducted for wrong answer
                - Time allowed            : 2 hours
                - Question types          : factual, conceptual, application-based

                """.formatted(QUESTIONS_PER_SET, OPTIONS_PER_Q);
    }

    /** Instructs generation of 4 unique sets. */
    private String setInstruction() {
        return """
                SET INSTRUCTION:
                - Generate exactly %d complete sets of question papers: Set 1, Set 2, Set 3, Set 4.
                - Each set must contain %d unique questions.
                - Questions must NOT be repeated across sets.
                - Clearly label each set with a header:
                    === SET <N> ===

                """.formatted(SETS, QUESTIONS_PER_SET);
    }

    /** Instructs the model to include both Junior and Senior difficulty levels. */
    private String levelInstruction() {
        return """
                DIFFICULTY LEVEL:
                - Within each set, include:
                    * 50 JUNIOR level questions  — foundational knowledge, direct recall
                    * 50 SENIOR level questions  — analytical, applied, higher-order thinking
                - Clearly mark each question block:
                    [JUNIOR LEVEL]
                    [SENIOR LEVEL]

                """;
    }

    /** Appends syllabus keywords to guide topic coverage. */
    private String keywordInstruction(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return """
                TOPIC COVERAGE:
                Ensure questions cover a wide range of the following topics and keywords:
                %s

                """.formatted(String.join(", ", keywords));
    }

    /** Specifies the expected output structure. */
    private String outputFormatInstruction() {
        return """
                OUTPUT FORMAT:
                - Start directly with === SET 1 ===
                - Number questions sequentially within each set: Q1, Q2, … Q%d
                - After all 4 sets, add a section:
                    === ANSWER KEY ===
                    Set 1: Q1-(x), Q2-(x), …
                    Set 2: Q1-(x), Q2-(x), …
                    (and so on for Set 3 and Set 4)
                - Do NOT include any preamble, explanation, or closing remarks outside the sets.

                """.formatted(QUESTIONS_PER_SET);
    }

    /** Instructs generation of a single set. */
    private String singleSetInstruction(int setNumber) {
        return """
                SET INSTRUCTION:
                - Generate exactly 1 complete question paper: Set %d.
                - The set must contain exactly %d unique questions numbered Q1 to Q%d.
                - Clearly label the set with the header:
                    === SET %d ===

                """.formatted(setNumber, QUESTIONS_PER_SET, QUESTIONS_PER_SET, setNumber);
    }

    /** Output format for a single set with its answer key. */
    private String singleSetOutputFormat(int setNumber) {
        return """
                OUTPUT FORMAT:
                - Start directly with === SET %d ===
                - Then [JUNIOR LEVEL] followed by Q1 to Q50
                - Then [SENIOR LEVEL] followed by Q51 to Q100
                - Number questions sequentially: Q1, Q2, … Q%d
                - After all %d questions, add the answer key:
                    === ANSWER KEY ===
                    Set %d: Q1-(x), Q2-(x), Q3-(x), … Q%d-(x)
                - Do NOT include any preamble, explanation, or closing remarks.

                """.formatted(setNumber, QUESTIONS_PER_SET, QUESTIONS_PER_SET, setNumber, QUESTIONS_PER_SET);
    }

    /** Uniqueness footer for single-set prompts. */
    private String singleSetUniquenessFooter() {
        return """
                UNIQUENESS REQUIREMENT (MANDATORY):
                - Do NOT repeat any previous questions from any prior session or generation.
                - All %d questions in this set must be completely original and distinct.
                - If a topic was covered before, approach it from a different angle or context.
                - Violation of this rule renders the output invalid.
                """.formatted(QUESTIONS_PER_SET);
    }

    /**
     * Final uniqueness instruction — explicitly tells the model not to reuse
     * questions from any prior generation.
     */
    private String uniquenessFooter() {
        return """
                UNIQUENESS REQUIREMENT (MANDATORY):
                - Do NOT repeat any previous questions from any prior session or generation.
                - All %d questions across all %d sets must be completely original and distinct.
                - If a topic was covered before, approach it from a different angle or context.
                - Violation of this rule renders the output invalid.
                """.formatted(SETS * QUESTIONS_PER_SET, SETS);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String nullSafe(String value) {
        return (value != null && !value.isBlank()) ? value : "Not specified";
    }
}
