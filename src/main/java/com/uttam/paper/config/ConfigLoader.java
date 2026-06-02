package com.uttam.paper.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strongly-typed configuration loaded from {@code config.properties}.
 */
@Getter
@Setter
@ToString(exclude = "geminiApiKey")
@Validated
@Configuration
@PropertySource("classpath:config.properties")
public class ConfigLoader {

    /** Google Gemini API key */
    @Value("${gemini.api.key}")
    @NotBlank(message = "gemini.api.key must not be blank in config.properties")
    private String geminiApiKey;

    /** Primary Gemini model endpoint URL */
    @Value("${gemini.api.url}")
    @NotBlank(message = "gemini.api.url must not be blank in config.properties")
    private String geminiApiUrl;

    /**
     * Comma-separated fallback model URLs tried in order when the primary model
     * returns HTTP 429 (quota exhausted). Optional — defaults to empty.
     */
    @Value("${gemini.fallback.urls:}")
    private String geminiFallbackUrls;

    /** Root directory for generated PDFs and history files */
    @Value("${output.dir:output/papers}")
    @NotBlank(message = "output.dir must not be blank in config.properties")
    private String outputDir;

    /** Cron expression for the daily scheduler (Spring 6-part cron) */
    @Value("${schedule.cron:0 0 8 * * *}")
    private String scheduleCron;

    /** Total number of days the scheduler will run from the first execution */
    @Value("${duration.days:90}")
    @Min(value = 1, message = "duration.days must be >= 1")
    private int durationDays;

    /**
     * Returns all model URLs to try in order: primary first, then fallbacks.
     */
    public List<String> getAllApiUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(geminiApiUrl.trim());
        if (geminiFallbackUrls != null && !geminiFallbackUrls.isBlank()) {
            Arrays.stream(geminiFallbackUrls.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(urls::add);
        }
        return urls;
    }
}

