package com.uttam.paper.service;

import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.dto.gemini.GeminiRequest;
import com.uttam.paper.dto.gemini.GeminiResponse;
import com.uttam.paper.exception.GeminiApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Service for interacting with the Google Gemini API.
 *
 * <p><b>Fallback strategy:</b>
 * <ol>
 *   <li>Try the primary model. If it returns 429 (quota exhausted), move to the next fallback model.</li>
 *   <li>On 5xx / network error: retry the same model up to {@value MAX_RETRIES} times with exponential back-off.</li>
 *   <li>On other 4xx (400, 401, 403…): fail immediately — no retry, no fallback.</li>
 *   <li>If all configured models are exhausted: return {@code ""} so the caller handles it gracefully.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private static final int    MAX_RETRIES      = 3;
    private static final long   RETRY_DELAY_MS   = 5_000;  // 5 s initial back-off
    private static final double RETRY_MULTIPLIER = 2.0;    // → 5 s, 10 s, 20 s

    private final RestTemplate restTemplate;
    private final ConfigLoader configLoader;

    /**
     * Sends {@code prompt} to the Gemini API and returns the generated text.
     *
     * <p>Tries each model URL returned by {@link ConfigLoader#getAllApiUrls()} in order,
     * falling back on 429 quota errors. Returns {@code ""} if all models fail.
     *
     * @param prompt the text prompt to send
     * @return generated text, or {@code ""} if all models are exhausted
     */
    public String generateQuestions(String prompt) {
        List<String> modelUrls = configLoader.getAllApiUrls();
        HttpEntity<GeminiRequest> request = buildRequest(prompt);

        for (int modelIdx = 0; modelIdx < modelUrls.size(); modelIdx++) {
            String modelUrl  = modelUrls.get(modelIdx).trim();
            String modelName = extractModelName(modelUrl);
            log.info("=== Gemini model {}/{}: {} | prompt {} chars ===",
                    modelIdx + 1, modelUrls.size(), modelName, prompt.length());

            boolean tryNextModel = false;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                log.info("Model {} — attempt {}/{}", modelName, attempt, MAX_RETRIES);
                try {
                    String url = modelUrl + "?key=" + configLoader.getGeminiApiKey().trim();
                    ResponseEntity<GeminiResponse> response =
                            restTemplate.exchange(url, HttpMethod.POST, request, GeminiResponse.class);
                    return parseResponse(response);

                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        String body = e.getResponseBodyAsString();
                        long retryDelayMs = parseRetryDelayMs(body);
                        boolean isRpmLimit = retryDelayMs <= 65_000; // per-minute = short delay

                        if (isRpmLimit && attempt < MAX_RETRIES) {
                            // Per-minute throttle — wait for window to reset, then retry same model
                            log.warn("Model {} RPM limit (429), retryDelay={}ms — waiting before retry (attempt {}/{}).",
                                    modelName, retryDelayMs, attempt, MAX_RETRIES);
                            sleep(retryDelayMs + 2_000);
                            continue; // retry same model

                        } else {
                            // Daily quota exhausted or RPM retries used up — switch to next model
                            boolean hasNext = modelIdx < modelUrls.size() - 1;
                            log.warn("Model {} quota exhausted (429) body={} — {}",
                                    modelName, body,
                                    hasNext ? "switching to next fallback model." : "no more fallback models.");
                            tryNextModel = true;
                            break;
                        }
                    }
                    if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                        // Model not available via this endpoint — try next model
                        log.warn("Model {} returned 404 (not available): {} — trying next model.",
                                modelName, e.getResponseBodyAsString());
                        tryNextModel = true;
                        break;
                    }
                    // Other 4xx (400, 401, 403…) — not retriable and no fallback
                    log.error("Gemini client error [{}] on model {}: {}",
                            e.getStatusCode(), modelName, e.getResponseBodyAsString());
                    throw new GeminiApiException(
                            "Gemini API client error: " + e.getStatusCode()
                                    + " — " + e.getResponseBodyAsString(), e);

                } catch (HttpServerErrorException | ResourceAccessException e) {
                    log.warn("⟳ Model {} transient error (attempt {}/{}) — {}: {}",
                            modelName, attempt, MAX_RETRIES,
                            e.getClass().getSimpleName(), e.getMessage());
                    if (attempt < MAX_RETRIES) {
                        sleep(backoffMs(attempt));
                    } else {
                        log.warn("Model {} failed after {} attempts — trying next model.", modelName, MAX_RETRIES);
                        tryNextModel = true;
                    }
                }
            }

            if (!tryNextModel) break; // should not be reached (success already returned)
        }

        log.error("All {}/{} configured Gemini model(s) failed to produce a response.", modelUrls.size(), modelUrls.size());
        return "";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private HttpEntity<GeminiRequest> buildRequest(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return new HttpEntity<>(GeminiRequest.of(prompt), headers);
    }

    private String parseResponse(ResponseEntity<GeminiResponse> response) {
        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            log.warn("Unexpected Gemini API response status: {}", response.getStatusCode());
            return "";
        }

        GeminiResponse body = response.getBody();

        if (body.getPromptFeedback() != null && body.getPromptFeedback().getBlockReason() != null) {
            log.warn("Gemini blocked the prompt. Reason: {}", body.getPromptFeedback().getBlockReason());
            return "";
        }

        String text = body.extractText();
        log.info("Gemini responded with {} chars", text.length());
        return text;
    }

    /** Extracts model name from URL, e.g. "gemini-2.5-flash" from ".../models/gemini-2.5-flash:generateContent". */
    private String extractModelName(String url) {
        try {
            String path = url.substring(url.lastIndexOf("/models/") + "/models/".length());
            return path.contains(":") ? path.substring(0, path.indexOf(":")) : path;
        } catch (Exception e) {
            return url;
        }
    }

    private long backoffMs(int attempt) {
        return (long) (RETRY_DELAY_MS * Math.pow(RETRY_MULTIPLIER, attempt - 1));
    }

    /** Parses the retryDelay from a Gemini 429 response body, e.g. {"retryDelay":"1s"}. Returns 60000ms if unparseable. */
    private long parseRetryDelayMs(String responseBody) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"retryDelay\":\\s*\"([0-9.]+)s\"")
                    .matcher(responseBody);
            if (m.find()) {
                return (long) (Double.parseDouble(m.group(1)) * 1000);
            }
        } catch (Exception ignored) {}
        return 60_000; // conservative default
    }

    private void sleep(long ms) {
        try {
            log.info("Waiting {}ms before retry...", ms);
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
