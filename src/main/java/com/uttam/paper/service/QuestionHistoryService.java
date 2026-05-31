package com.uttam.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.exception.QuestionHistoryException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Persists previously generated questions to {@code previous_questions.json}.
 *
 * <p>Thread-safety: a {@link ReentrantReadWriteLock} allows concurrent reads while
 * serialising all writes. The critical {@link #saveIfAllUnique} operation acquires
 * the write lock for the entire check-then-save window, eliminating the TOCTOU race
 * where two concurrent runs could both pass {@code isUnique()} and both write
 * duplicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionHistoryService {

    private static final String HISTORY_FILE = "previous_questions.json";

    private final ConfigLoader configLoader;
    private final ObjectMapper objectMapper;

    private Path historyFilePath;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        try {
            Path outputDir = Paths.get(configLoader.getOutputDir());
            Files.createDirectories(outputDir);
            historyFilePath = outputDir.resolve(HISTORY_FILE);

            if (!Files.exists(historyFilePath)) {
                objectMapper.writeValue(historyFilePath.toFile(), Collections.emptyList());
                log.info("Created question history file: {}", historyFilePath.toAbsolutePath());
            } else {
                log.info("Question history file found: {} ({} bytes)",
                        historyFilePath.toAbsolutePath(), Files.size(historyFilePath));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise QuestionHistoryService: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Atomically checks uniqueness AND saves in a single write-lock window.
     *
     * <p>This prevents the race condition where two concurrent callers both pass
     * an independent {@code isUnique()} check and then both write their questions.
     *
     * @param newQuestions questions to check and persist
     * @return {@code true} if all questions were new and have been saved;
     *         {@code false} if any duplicate was found (nothing is written)
     * @throws QuestionHistoryException if the file cannot be read or written
     */
    public boolean saveIfAllUnique(List<String> newQuestions) {
        if (newQuestions == null || newQuestions.isEmpty()) {
            log.warn("saveIfAllUnique called with empty list — treating as unique, nothing saved.");
            return true;
        }

        lock.writeLock().lock();
        try {
            List<String> existing = readFromDisk();
            Set<String> historicalNormalised = existing.stream()
                    .map(QuestionHistoryService::normalise)
                    .collect(Collectors.toSet());

            for (String question : newQuestions) {
                if (historicalNormalised.contains(normalise(question))) {
                    log.warn("Duplicate detected: \"{}\"",
                            question.length() > 80 ? question.substring(0, 80) + "…" : question);
                    return false;
                }
            }

            // All unique — append and persist
            Set<String> combined = new LinkedHashSet<>(existing);
            newQuestions.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(q -> !q.isBlank())
                    .forEach(combined::add);

            writeToDisk(new ArrayList<>(combined));
            log.info("Saved {} new questions. Total history: {}", newQuestions.size(), combined.size());
            return true;

        } catch (IOException e) {
            throw new QuestionHistoryException("Failed to read/write question history", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads all stored question strings (read-only, concurrent-safe).
     *
     * @throws QuestionHistoryException if the file cannot be read
     */
    public List<String> loadQuestions() {
        lock.readLock().lock();
        try {
            return readFromDisk();
        } catch (IOException e) {
            throw new QuestionHistoryException("Failed to read question history", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks uniqueness without persisting (for pre-flight checks only).
     * Prefer {@link #saveIfAllUnique} for the generation pipeline.
     */
    public boolean isUnique(List<String> newQuestions) {
        if (newQuestions == null || newQuestions.isEmpty()) return true;

        lock.readLock().lock();
        try {
            Set<String> history = readFromDisk().stream()
                    .map(QuestionHistoryService::normalise)
                    .collect(Collectors.toSet());

            for (String q : newQuestions) {
                if (history.contains(normalise(q))) {
                    log.warn("Duplicate detected (read-only check): \"{}\"",
                            q.length() > 80 ? q.substring(0, 80) + "…" : q);
                    return false;
                }
            }
            log.info("Uniqueness check passed for {} questions.", newQuestions.size());
            return true;
        } catch (IOException e) {
            throw new QuestionHistoryException("Failed to read question history for uniqueness check", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns count of stored questions. */
    public int historySize() {
        return loadQuestions().size();
    }

    /** Clears all history (admin use only). */
    public void clearHistory() {
        lock.writeLock().lock();
        try {
            objectMapper.writeValue(historyFilePath.toFile(), Collections.emptyList());
            log.warn("Question history cleared.");
        } catch (IOException e) {
            throw new QuestionHistoryException("Failed to clear question history", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> readFromDisk() throws IOException {
        File file = historyFilePath.toFile();
        if (!file.exists() || file.length() == 0) return new ArrayList<>();
        return objectMapper.readValue(file, new TypeReference<List<String>>() {});
    }

    private void writeToDisk(List<String> questions) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyFilePath.toFile(), questions);
    }

    private static String normalise(String q) {
        if (q == null) return "";
        return q.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
