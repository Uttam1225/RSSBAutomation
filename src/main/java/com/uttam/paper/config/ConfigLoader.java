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

/**
 * Strongly-typed configuration loaded from {@code config.properties}.
 *
 * <p>Uses {@code @Value} for explicit property binding since the property keys
 * span multiple namespaces (gemini.*, output.*, schedule.*) with no shared prefix.
 * All fields are validated at startup via {@code @Validated}.
 */
@Getter
@Setter
@ToString(exclude = "geminiApiKey")  // never log the API key
@Validated
@Configuration
@PropertySource("classpath:config.properties")
public class ConfigLoader {

    /** Google Gemini API key */
    @Value("${gemini.api.key}")
    @NotBlank(message = "gemini.api.key must not be blank in config.properties")
    private String geminiApiKey;

    /** Google Gemini API endpoint URL */
    @Value("${gemini.api.url}")
    @NotBlank(message = "gemini.api.url must not be blank in config.properties")
    private String geminiApiUrl;

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
}

