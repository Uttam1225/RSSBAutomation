package com.uttam.paper.util;

import com.uttam.paper.model.Category;
import com.uttam.paper.model.NotificationData;
import com.uttam.paper.model.PaperType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Builds a role- and type-aware prompt for the Gemini API.
 *
 * <p>The 4 sets generated per run are:
 * <ul>
 *   <li>Set 1 — Junior  / Non-Technical</li>
 *   <li>Set 2 — Junior  / Technical</li>
 *   <li>Set 3 — Senior  / Non-Technical</li>
 *   <li>Set 4 — Senior  / Technical</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptBuilder {

    private static final DateTimeFormatter DATE_FMT       = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int               QUESTIONS_PER_SET = 100;
    private static final int               OPTIONS_PER_Q     = 4;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a prompt for a single set identified by role and paper type.
     *
     * @param data      parsed notification data
     * @param setNumber set number (1–4)
     * @param role      JUNIOR or SENIOR
     * @param type      TECHNICAL or NON_TECHNICAL
     * @return ready-to-send prompt string
     */
    public String buildForSet(NotificationData data, int setNumber, Category role, PaperType type) {
        String date = LocalDate.now().format(DATE_FMT);
        String seed = UUID.randomUUID().toString();

        log.info("Building prompt | date={} | seed={} | set={} | role={} | type={} | title={}",
                date, seed, setNumber, role, type, data.getTitle());

        return new StringBuilder()
                .append(uniquenessHeader(date, seed, setNumber, role, type))
                .append(bilingualInstruction())
                .append(examContext(data))
                .append(examFormatInstruction())
                .append(paperTypeInstruction(role, type, data))
                .append(keywordInstruction(data.getKeywords(), type))
                .append(outputFormat(setNumber))
                .append(uniquenessFooter())
                .toString();
    }

    // -------------------------------------------------------------------------
    // Prompt sections
    // -------------------------------------------------------------------------

    private String uniquenessHeader(String date, String seed, int setNumber,
                                    Category role, PaperType type) {
        return """
                === SESSION METADATA (do not include in output) ===
                Generation Date : %s
                Unique Seed     : %s
                Set Number      : %d
                Role Level      : %s
                Paper Type      : %s
                =====================================================

                """.formatted(date, seed, setNumber,
                role.name(), type.name().replace('_', ' '));
    }

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

    private String examFormatInstruction() {
        return """
                EXAM FORMAT (RSSB Standard):
                - Total questions per paper : %d MCQs
                - Options per question      : %d (a, b, c, d)
                - Each question carries     : 1 mark
                - Negative marking          : 1/3 mark deducted for wrong answer
                - Time allowed             : 2 hours

                """.formatted(QUESTIONS_PER_SET, OPTIONS_PER_Q);
    }

    /**
     * Core instruction section — varies by role (JUNIOR/SENIOR) and type (TECHNICAL/NON_TECHNICAL).
     */
    private String paperTypeInstruction(Category role, PaperType type, NotificationData data) {
        String roleDesc   = roleDescription(role);
        String topicBlock = topicBlock(role, type, data);

        return """
                PAPER SPECIFICATION:
                Role Level : %s
                Paper Type : %s

                %s
                %s

                SET INSTRUCTION:
                - Generate exactly 1 complete question paper for this role and type.
                - The paper must contain exactly %d unique questions numbered Q1 to Q%d.
                - ALL questions must be appropriate for the %s role at %s level.
                - Questions must NOT overlap with any other set in this run.

                """.formatted(
                role.name(),
                type.name().replace('_', ' '),
                roleDesc,
                topicBlock,
                QUESTIONS_PER_SET, QUESTIONS_PER_SET,
                type.name().replace('_', ' ').toLowerCase(),
                role.name().toLowerCase());
    }

    private String roleDescription(Category role) {
        return switch (role) {
            case JUNIOR -> """
                    JUNIOR LEVEL GUIDELINES:
                    - Questions should test foundational knowledge and basic understanding.
                    - Use direct recall, straightforward application, and simple reasoning.
                    - Suitable for a candidate with basic qualification and 0-3 years experience.
                    - Avoid highly complex, multi-step, or advanced analytical questions.""";
            case SENIOR -> """
                    SENIOR LEVEL GUIDELINES:
                    - Questions should test advanced knowledge, analysis, and critical thinking.
                    - Include application-based, scenario-based, and higher-order thinking questions.
                    - Suitable for a candidate with 5+ years experience or higher qualification.
                    - Include multi-concept, policy-level, and managerial-level questions where relevant.""";
        };
    }

    private String topicBlock(Category role, PaperType type, NotificationData data) {
        return switch (type) {
            case NON_TECHNICAL -> """
                    NON-TECHNICAL TOPICS (General Ability & Knowledge):
                    Cover a broad mix of the following topics:
                    1. History, Art & Culture of Rajasthan
                    2. Geography of Rajasthan (rivers, lakes, districts, climate)
                    3. General Science (Physics, Chemistry, Biology — Class X level)
                    4. Current Affairs (Rajasthan & National — last 1 year)
                    5. Indian Constitution & Polity
                    6. Economy of Rajasthan & India
                    7. Logical Reasoning & Analytical Ability
                    8. Data Interpretation (charts, tables, graphs)
                    9. Basic Numeracy & Number Systems (Class X level)
                    10. Decision Making & Problem Solving
                    Ensure all 100 questions are purely general/non-technical in nature.""";
            case TECHNICAL -> """
                    TECHNICAL TOPICS (Job-Specific Knowledge):
                    The technical questions must be directly relevant to the post: %s
                    Derive technical topics from the syllabus provided above.
                    Typical technical areas include (adapt to the specific post):
                    - Core subject knowledge required for the post
                    - Relevant laws, rules, policies, and procedures
                    - Tools, methods, and practices used in the role
                    - Domain-specific terminology and concepts
                    - Applied problem-solving in the job domain
                    Ensure all 100 questions are technical and job-specific for this post.
                    Do NOT include general knowledge, history, or geography questions."""
                    .formatted(nullSafe(data.getTitle()));
        };
    }

    private String keywordInstruction(List<String> keywords, PaperType type) {
        if (keywords == null || keywords.isEmpty()) return "";
        if (type == PaperType.NON_TECHNICAL) return "";   // general topics don't need keyword steering
        return """
                ADDITIONAL TOPIC KEYWORDS (from notification):
                %s

                """.formatted(String.join(", ", keywords));
    }

    private String outputFormat(int setNumber) {
        return """
                OUTPUT FORMAT:
                - Start directly with === SET %d ===
                - Number questions sequentially: Q1, Q2, ... Q%d
                - After all %d questions, add the answer key:
                    === ANSWER KEY ===
                    Set %d: Q1-(x), Q2-(x), Q3-(x), ... Q%d-(x)
                - Do NOT include any preamble, explanation, or closing remarks.

                """.formatted(setNumber, QUESTIONS_PER_SET, QUESTIONS_PER_SET,
                              setNumber, QUESTIONS_PER_SET);
    }

    private String uniquenessFooter() {
        return """
                UNIQUENESS REQUIREMENT (MANDATORY):
                - Do NOT repeat any question from any prior generation session.
                - All %d questions must be completely original and distinct.
                - If a topic was covered before, approach it from a different angle or context.
                - Violation of this rule renders the output invalid.
                """.formatted(QUESTIONS_PER_SET);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String nullSafe(String value) {
        return (value != null && !value.isBlank()) ? value : "Not specified";
    }
}
