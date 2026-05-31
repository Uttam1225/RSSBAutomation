package com.uttam.paper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Holds parsed notification data fetched from the RSSB/exam notification source.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationData {

    /** Title of the exam notification */
    private String title;

    /** Syllabus topics extracted from the notification */
    private String syllabus;

    /** Exam pattern details (e.g., number of questions, marks, duration) */
    private String examPattern;

    /** Keywords derived from the syllabus for AI prompt generation */
    private List<String> keywords;
}
