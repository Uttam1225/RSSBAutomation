package com.uttam.paper.service;

import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.exception.PaperGenerationException;
import com.uttam.paper.exception.SchedulerConflictException;
import com.uttam.paper.model.QuestionPaper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Scheduled service that automatically generates question papers once per day.
 *
 * <p><b>Thread safety:</b> An {@link AtomicBoolean} {@code isRunning} flag prevents
 * concurrent execution from both the cron trigger and manual API calls. The flag
 * is always reset in a {@code finally} block to prevent permanent lockout on error.
 *
 * <p><b>Idempotency:</b> Each successfully executed date is appended to
 * {@code executed-dates.log}. If today's date is already present the job skips.
 *
 * <p><b>Duration window:</b> Stops automatically after {@code duration.days} days
 * (default 90) from the first recorded execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private static final String LOG_FILE = "executed-dates.log";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ConfigLoader         configLoader;
    private final QuestionPaperService questionPaperService;

    /** Guards against concurrent cron + manual execution. */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Path logFilePath;

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        try {
            Path outputDir = Paths.get(configLoader.getOutputDir());
            Files.createDirectories(outputDir);
            logFilePath = outputDir.resolve(LOG_FILE);

            if (!Files.exists(logFilePath)) {
                Files.createFile(logFilePath);
                log.info("Created execution log: {}", logFilePath.toAbsolutePath());
            } else {
                log.info("Execution log loaded — {} date(s) recorded. Path: {}",
                        loadExecutedDates().size(), logFilePath.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("SchedulerService init failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled job
    // -------------------------------------------------------------------------

    @Scheduled(cron = "${schedule.cron:0 0 8 * * *}")
    public void runDailyJob() {
        LocalDate today = LocalDate.now();
        log.info("=== Scheduler triggered for: {} ===", today);

        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Scheduler skipped for {} — a generation job is already running.", today);
            return;
        }

        try {
            executeFor(today, false);
        } finally {
            isRunning.set(false);
            log.info("=== Scheduler job finished for: {} ===", today);
        }
    }

    // -------------------------------------------------------------------------
    // Manual trigger (REST API)
    // -------------------------------------------------------------------------

    /**
     * Fires paper generation asynchronously in a separate thread.
     * Returns immediately; use {@link #isJobRunning()} / PDF file count to track completion.
     */
    @Async
    public void triggerAsync(LocalDate date, boolean force) {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Skipping async trigger for {} — a generation job is already running.", date);
            return;
        }
        try {
            executeFor(date, force);
        } catch (Exception e) {
            log.error("Async generation failed for {}: {}", date, e.getMessage(), e);
        } finally {
            isRunning.set(false);
            log.info("=== Async generation finished for: {} ===", date);
        }
    }

    /**
     * Manually triggers generation for {@code date}.
     *
     * @param date  target date
     * @param force if {@code true}, bypasses the already-executed guard
     * @return generated papers
     * @throws SchedulerConflictException if a job is already running
     * @throws SchedulerConflictException if date already executed and force=false
     */
    public List<QuestionPaper> triggerManual(LocalDate date, boolean force) {
        log.info("Manual trigger requested: date={} force={}", date, force);

        if (!isRunning.compareAndSet(false, true)) {
            throw new SchedulerConflictException(
                    "A generation job is already in progress. Please wait and retry.");
        }

        try {
            return executeFor(date, force);
        } finally {
            isRunning.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Public utilities
    // -------------------------------------------------------------------------

    public Set<String> loadExecutedDates() {
        try {
            if (!Files.exists(logFilePath)) return new HashSet<>();
            return Files.readAllLines(logFilePath, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            log.error("Failed to read execution log: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    public int daysRemaining() {
        Set<String> dates = loadExecutedDates();
        if (dates.isEmpty()) return configLoader.getDurationDays();

        LocalDate earliest = dates.stream()
                .map(d -> LocalDate.parse(d, FMT))
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(earliest, LocalDate.now());
        return (int) Math.max(0, configLoader.getDurationDays() - elapsed);
    }

    public boolean isJobRunning() {
        return isRunning.get();
    }

    // -------------------------------------------------------------------------
    // Core execution logic
    // -------------------------------------------------------------------------

    private List<QuestionPaper> executeFor(LocalDate date, boolean force) {
        // Guard: already executed?
        if (!force && alreadyExecuted(date)) {
            log.info("Date {} already executed — skipping. Use force=true to override.", date);
            throw new SchedulerConflictException(
                    "Papers already generated for " + date + ". Use force=true to regenerate.");
        }

        // Guard: duration window exceeded?
        if (isDurationExceeded(date)) {
            log.warn("Execution window of {} days exceeded — skipping {}.",
                    configLoader.getDurationDays(), date);
            return Collections.emptyList();
        }

        // Generate — only throws PaperGenerationException if ALL sets fail (0 produced)
        List<QuestionPaper> papers = questionPaperService.generateDailyPapers();

        // Mark executed as long as at least 1 set was saved
        if (!papers.isEmpty()) {
            try {
                markExecuted(date);
                log.info("Date {} recorded in execution log ({}/4 sets generated).",
                        date, papers.size());
            } catch (IOException e) {
                log.error("Failed to record execution date {}: {}", date, e.getMessage(), e);
            }
        }

        return papers;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean alreadyExecuted(LocalDate date) {
        return loadExecutedDates().contains(date.format(FMT));
    }

    private boolean isDurationExceeded(LocalDate today) {
        Set<String> dates = loadExecutedDates();
        if (dates.isEmpty()) return false;

        LocalDate startDate = dates.stream()
                .map(d -> LocalDate.parse(d, FMT))
                .min(LocalDate::compareTo)
                .orElse(today);

        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate, today);
        return elapsed >= configLoader.getDurationDays();
    }

    private synchronized void markExecuted(LocalDate date) throws IOException {
        String entry = date.format(FMT) + System.lineSeparator();
        Files.write(logFilePath, entry.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND, StandardOpenOption.CREATE);
    }
}
