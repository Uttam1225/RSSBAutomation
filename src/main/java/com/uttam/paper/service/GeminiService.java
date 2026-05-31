package com.uttam.paper.service;

import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.dto.gemini.GeminiRequest;
import com.uttam.paper.dto.gemini.GeminiResponse;
import com.uttam.paper.exception.GeminiApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Service for interacting with the Google Gemini API.
 *
 * <p>Sends a text prompt and returns the model's generated response as a String.
 * Uses Spring Retry to handle transient errors with exponential back-off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final RestTemplate restTemplate;
    private final ConfigLoader configLoader;

    /**
     * Sends {@code prompt} to the Gemini API and returns the generated text.
     *
     * <p>Retries up to 3 times on {@link ResourceAccessException} (timeout/network)
     * or {@link HttpServerErrorException} (5xx), with exponential back-off
     * starting at 2 s and doubling each attempt (2 s → 4 s → 8 s).
     *
     * @param prompt the text prompt to send
     * @return generated text from Gemini, never {@code null}
     */
    @Retryable(
        retryFor  = { ResourceAccessException.class, HttpServerErrorException.class },
        maxAttempts = 3,
        backoff   = @Backoff(delay = 2000, multiplier = 2)
    )
    public String generateQuestions(String prompt) {
        log.info("Calling Gemini API. Prompt length: {} chars", prompt.length());

        String url = buildUrl();
        HttpEntity<GeminiRequest> request = buildRequest(prompt);

        try {
            ResponseEntity<GeminiResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST, request, GeminiResponse.class);

            return parseResponse(response);

        } catch (HttpClientErrorException e) {
            // 4xx errors — not retried (bad request, auth failure, quota exceeded)
            log.error("Gemini API client error [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GeminiApiException("Gemini API client error: " + e.getStatusCode()
                    + " — " + e.getResponseBodyAsString(), e);

        } catch (HttpServerErrorException e) {
            // 5xx — will be retried by @Retryable
            log.warn("Gemini API server error [{}], will retry…", e.getStatusCode());
            throw e;

        } catch (ResourceAccessException e) {
            // Timeout / network error — will be retried by @Retryable
            log.warn("Gemini API connection error, will retry… {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Recovery method invoked after all retry attempts are exhausted.
     * Returns an empty string so callers can handle gracefully.
     */
    @Recover
    public String recoverGenerateQuestions(Exception e, String prompt) {
        log.error("All Gemini API retry attempts failed for prompt (length={}). Cause: {}",
                prompt.length(), e.getMessage());
        return "";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildUrl() {
        String baseUrl = configLoader.getGeminiApiUrl().trim();
        String apiKey  = configLoader.getGeminiApiKey().trim();
        log.info("Gemini URL (trimmed): {}?key=***", baseUrl);
        // Append key as query param (Gemini supports both header and query-param auth)
        return baseUrl + "?key=" + apiKey;
    }

    private HttpEntity<GeminiRequest> buildRequest(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        // Key is sent as ?key= query param in buildUrl() — no Bearer header needed

        GeminiRequest body = GeminiRequest.of(prompt);
        return new HttpEntity<>(body, headers);
    }

    private String parseResponse(ResponseEntity<GeminiResponse> response) {
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            log.warn("Unexpected Gemini API response status: {}", response.getStatusCode());
            return "";
        }

        GeminiResponse geminiResponse = response.getBody();

        // Check for prompt blocking
        if (geminiResponse.getPromptFeedback() != null
                && geminiResponse.getPromptFeedback().getBlockReason() != null) {
            log.warn("Gemini blocked the prompt. Reason: {}",
                    geminiResponse.getPromptFeedback().getBlockReason());
            return "";
        }

        String text = geminiResponse.extractText();
        log.info("Gemini API responded with {} chars", text.length());
        return text;
    }
}
