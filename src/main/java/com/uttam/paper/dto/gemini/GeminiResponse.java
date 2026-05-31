package com.uttam.paper.dto.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Maps to Gemini API response:
 * {
 *   "candidates": [{
 *     "content": { "parts": [{ "text": "..." }] }
 *   }]
 * }
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiResponse {

    private List<Candidate> candidates;
    private PromptFeedback promptFeedback;

    // ---- Nested types -------------------------------------------------------

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candidate {
        private Content content;
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private List<Part> parts;
        private String role;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Part {
        private String text;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptFeedback {
        private String blockReason;
    }

    // ---- Convenience --------------------------------------------------------

    /**
     * Extracts the first text response from candidates, or empty string if absent.
     */
    public String extractText() {
        if (candidates == null || candidates.isEmpty()) return "";
        Candidate first = candidates.get(0);
        if (first.getContent() == null) return "";
        List<Part> parts = first.getContent().getParts();
        if (parts == null || parts.isEmpty()) return "";
        return parts.get(0).getText() != null ? parts.get(0).getText() : "";
    }
}
