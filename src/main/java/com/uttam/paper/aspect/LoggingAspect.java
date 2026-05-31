package com.uttam.paper.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * AOP aspect that provides cross-cutting logging for:
 *
 * <ul>
 *   <li><b>REST controllers</b>  — logs method, args summary, response status, duration</li>
 *   <li><b>Service layer</b>     — logs entry/exit, duration, exceptions</li>
 *   <li><b>Gemini API calls</b>  — logs prompt length, response length, retries, failures</li>
 *   <li><b>Duplicate detection</b> — logs uniqueness check results</li>
 *   <li><b>Generation success</b> — logs paper count, question count, PDF paths</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {

    // ── Pointcuts ─────────────────────────────────────────────────────────────

    @Pointcut("within(com.uttam.paper.controller..*)")
    public void controllerLayer() {}

    @Pointcut("within(com.uttam.paper.service..*)")
    public void serviceLayer() {}

    @Pointcut("execution(* com.uttam.paper.service.GeminiService.generateQuestions(..))")
    public void geminiCall() {}

    @Pointcut("execution(* com.uttam.paper.service.QuestionHistoryService.isUnique(..))")
    public void uniquenessCheck() {}

    @Pointcut("execution(* com.uttam.paper.service.QuestionPaperService.generateDailyPapers(..))")
    public void paperGeneration() {}

    @Pointcut("execution(* com.uttam.paper.service.PdfService.generatePdf(..))")
    public void pdfGeneration() {}

    // ── Controller logging ────────────────────────────────────────────────────

    @Around("controllerLayer()")
    public Object logController(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig    = (MethodSignature) pjp.getSignature();
        String          method = sig.getDeclaringType().getSimpleName() + "." + sig.getName();
        String          args   = summariseArgs(pjp.getArgs());

        log.info("→ API CALL  [{}.{}]  args=[{}]", sig.getDeclaringType().getSimpleName(),
                sig.getName(), args);

        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();
            log.info("← API OK    [{}]  duration={}ms", method, sw.getTotalTimeMillis());
            return result;
        } catch (Exception e) {
            sw.stop();
            log.error("← API ERROR [{}]  duration={}ms  error={}", method,
                    sw.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }

    // ── Service logging ───────────────────────────────────────────────────────

    @Around("serviceLayer() && !geminiCall() && !uniquenessCheck() && !paperGeneration() && !pdfGeneration()")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig    = (MethodSignature) pjp.getSignature();
        String          method = sig.getDeclaringType().getSimpleName() + "." + sig.getName();

        log.debug("▶ SERVICE   [{}]", method);
        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();
            log.debug("◀ SERVICE   [{}]  {}ms", method, sw.getTotalTimeMillis());
            return result;
        } catch (Exception e) {
            sw.stop();
            log.warn("✗ SERVICE   [{}]  {}ms  error={}", method,
                    sw.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }

    // ── Gemini API call logging ───────────────────────────────────────────────

    @Around("geminiCall()")
    public Object logGeminiCall(ProceedingJoinPoint pjp) throws Throwable {
        String prompt       = (String) pjp.getArgs()[0];
        int    promptLength = prompt != null ? prompt.length() : 0;

        log.info("┌─ GEMINI REQUEST  promptLength={} chars", promptLength);
        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();
            int responseLength = result instanceof String ? ((String) result).length() : 0;
            log.info("└─ GEMINI RESPONSE  responseLength={} chars  duration={}ms",
                    responseLength, sw.getTotalTimeMillis());
            if (responseLength == 0) {
                log.warn("   ⚠ Gemini returned EMPTY response after {}ms", sw.getTotalTimeMillis());
            }
            return result;
        } catch (Exception e) {
            sw.stop();
            log.error("└─ GEMINI FAILED  duration={}ms  error={}", sw.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }

    // ── Uniqueness / duplicate detection logging ──────────────────────────────

    @Around("uniquenessCheck()")
    public Object logUniquenessCheck(ProceedingJoinPoint pjp) throws Throwable {
        @SuppressWarnings("unchecked")
        java.util.List<String> questions = (java.util.List<String>) pjp.getArgs()[0];
        int total = questions != null ? questions.size() : 0;

        log.info("┌─ UNIQUENESS CHECK  checking={} questions", total);
        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();
            boolean unique = Boolean.TRUE.equals(result);
            if (unique) {
                log.info("└─ UNIQUENESS PASS  ✓ all {} questions are new  duration={}ms",
                        total, sw.getTotalTimeMillis());
            } else {
                log.warn("└─ DUPLICATE DETECTED  ✗ one or more of {} questions already exist  duration={}ms",
                        total, sw.getTotalTimeMillis());
            }
            return result;
        } catch (Exception e) {
            sw.stop();
            log.error("└─ UNIQUENESS ERROR  duration={}ms  error={}", sw.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }

    // ── Full paper generation logging ─────────────────────────────────────────

    @Around("paperGeneration()")
    public Object logPaperGeneration(ProceedingJoinPoint pjp) throws Throwable {
        log.info("╔══════════════════════════════════════════");
        log.info("║  PAPER GENERATION STARTED");
        log.info("╚══════════════════════════════════════════");

        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();

            @SuppressWarnings("unchecked")
            java.util.List<com.uttam.paper.model.QuestionPaper> papers =
                    (java.util.List<com.uttam.paper.model.QuestionPaper>) result;

            if (papers == null || papers.isEmpty()) {
                log.error("╔══════════════════════════════════════════");
                log.error("║  PAPER GENERATION FAILED — empty result");
                log.error("║  Duration: {}ms", sw.getTotalTimeMillis());
                log.error("╚══════════════════════════════════════════");
            } else {
                int totalQ = papers.stream().mapToInt(p -> p.getQuestions().size()).sum();
                log.info("╔══════════════════════════════════════════");
                log.info("║  PAPER GENERATION SUCCESS ✓");
                log.info("║  Sets      : {}", papers.size());
                log.info("║  Questions : {}", totalQ);
                log.info("║  Duration  : {}ms", sw.getTotalTimeMillis());
                papers.forEach(p -> log.info("║  ├─ Set {} | {} | {} Qs | AK: {} entries",
                        p.getSetNumber(), p.getCategory(),
                        p.getQuestions().size(), p.getAnswerKey().size()));
                log.info("╚══════════════════════════════════════════");
            }
            return result;
        } catch (Exception e) {
            sw.stop();
            log.error("╔══════════════════════════════════════════");
            log.error("║  PAPER GENERATION EXCEPTION: {}", e.getMessage());
            log.error("║  Duration: {}ms", sw.getTotalTimeMillis());
            log.error("╚══════════════════════════════════════════");
            throw e;
        }
    }

    // ── PDF generation logging ────────────────────────────────────────────────

    @Around("pdfGeneration()")
    public Object logPdfGeneration(ProceedingJoinPoint pjp) throws Throwable {
        com.uttam.paper.model.QuestionPaper paper =
                (com.uttam.paper.model.QuestionPaper) pjp.getArgs()[0];

        log.info("▶ PDF GENERATE  set={} category={} questions={}",
                paper.getSetNumber(), paper.getCategory(), paper.getQuestions().size());

        StopWatch sw = new StopWatch();
        sw.start();
        try {
            Object result = pjp.proceed();
            sw.stop();
            log.info("◀ PDF SAVED  ✓  path={}  duration={}ms",
                    result, sw.getTotalTimeMillis());
            return result;
        } catch (Exception e) {
            sw.stop();
            log.error("✗ PDF FAILED  set={}  duration={}ms  error={}",
                    paper.getSetNumber(), sw.getTotalTimeMillis(), e.getMessage());
            throw e;
        }
    }

    // ── Retry event logging (called from GeminiService via Spring Retry listener) ─

    /**
     * Logs a retry warning. Called externally from {@code GeminiService} recover method,
     * or can be wired as a Spring Retry listener callback.
     */
    public static void logRetry(int attemptNumber, String reason) {
        log.warn("⟳ RETRY  attempt={} reason={}", attemptNumber, reason);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String summariseArgs(Object[] args) {
        if (args == null || args.length == 0) return "none";
        return Arrays.stream(args)
                .map(a -> {
                    if (a == null)            return "null";
                    if (a instanceof String s) return "\"" + (s.length() > 40
                                                        ? s.substring(0, 40) + "…" : s) + "\"";
                    if (a instanceof org.springframework.web.multipart.MultipartFile f)
                                              return "file(" + f.getOriginalFilename() + ")";
                    return a.getClass().getSimpleName();
                })
                .collect(Collectors.joining(", "));
    }
}
