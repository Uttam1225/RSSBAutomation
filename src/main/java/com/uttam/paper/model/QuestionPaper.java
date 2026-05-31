package com.uttam.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a complete question paper comprising multiple questions
 * and its corresponding answer key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionPaper {

    /** Identifies the set number (1–4) */
    private int setNumber;

    /** Role level: JUNIOR or SENIOR */
    private Category category;

    /** Paper type: TECHNICAL or NON_TECHNICAL */
    private PaperType paperType;

    /** List of questions in this paper */
    @Builder.Default
    private List<Question> questions = new ArrayList<>();

    /**
     * Answer key for this set.
     * Key   = question number (1-based)
     * Value = correct option letter, e.g. "a", "b", "c", "d"
     */
    @Builder.Default
    private Map<Integer, String> answerKey = new LinkedHashMap<>();
}
