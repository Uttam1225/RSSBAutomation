package com.uttam.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single exam question with its language.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Question {

    /** The question text */
    private String text;

    /** Language of the question: EN (English) or HIN (Hindi) */
    @Builder.Default
    private Language language = Language.EN;
}
