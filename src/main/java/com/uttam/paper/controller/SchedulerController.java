package com.uttam.paper.controller;

import com.uttam.paper.dto.ApiResponse;
import com.uttam.paper.model.QuestionPaper;
import com.uttam.paper.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller to inspect and manually control the SchedulerService.
 *
 * <p>Exceptions (SchedulerConflictException, PaperGenerationException, etc.)
 * propagate to {@link com.uttam.paper.exception.GlobalExceptionHandler}.
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final SchedulerService schedulerService;

    /**
     * GET /api/scheduler/status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Set<String> executed = schedulerService.loadExecutedDates();
        Map<String, Object> data = Map.of(
                "executedDates",  executed,
                "totalExecuted",  executed.size(),
                "daysRemaining",  schedulerService.daysRemaining(),
                "isRunning",      schedulerService.isJobRunning()
        );
        return ResponseEntity.ok(ApiResponse.ok("Scheduler status.", data));
    }

    /**
     * GET /api/scheduler/generateNow
     * Immediately generates papers and PDFs for today, bypassing the schedule.
     */
    @GetMapping("/generateNow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateNow() {
        log.info("Immediate generation requested for {}", LocalDate.now());
        List<QuestionPaper> papers = schedulerService.triggerManual(LocalDate.now(), true);
        return ResponseEntity.ok(ApiResponse.ok(
                "Papers and PDFs generated successfully for " + LocalDate.now() + ".",
                buildResultMap(papers, LocalDate.now())));
    }

    /**
     * POST /api/scheduler/trigger?date=2026-05-31&force=false
     * Manually triggers paper generation for a given date.
     * Returns 409 if date already executed (and force=false) or job already running.
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trigger(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {

        LocalDate targetDate = (date != null) ? date : LocalDate.now();
        log.info("Manual scheduler trigger: date={} force={}", targetDate, force);

        List<QuestionPaper> papers = schedulerService.triggerManual(targetDate, force);
        return ResponseEntity.ok(ApiResponse.ok(
                "Papers generated for " + targetDate + ".",
                buildResultMap(papers, targetDate)));
    }

    // -------------------------------------------------------------------------

    private Map<String, Object> buildResultMap(List<QuestionPaper> papers, LocalDate date) {
        return Map.of(
                "date",            date.toString(),
                "setsGenerated",   papers.size(),
                "totalQuestions",  papers.stream().mapToInt(p -> p.getQuestions().size()).sum(),
                "answerKeys",      papers.stream().collect(
                        Collectors.toMap(
                                p -> "Set" + p.getSetNumber(),
                                p -> p.getAnswerKey().size() + " entries"))
        );
    }
}
