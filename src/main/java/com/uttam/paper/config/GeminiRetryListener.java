package com.uttam.paper.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

/**
 * Spring Retry listener that logs every retry attempt made by {@code GeminiService}.
 *
 * <p>Registered automatically as a bean — Spring Retry picks it up from the context.
 */
@Slf4j
@Component
public class GeminiRetryListener implements RetryListener {

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {

        int attempt = context.getRetryCount();   // 0-based count of errors so far
        log.warn("⟳ GEMINI RETRY  attempt={}/3  cause=[{}] message={}",
                attempt,
                throwable.getClass().getSimpleName(),
                throwable.getMessage());
    }

    @Override
    public <T, E extends Throwable> boolean open(
            RetryContext context, RetryCallback<T, E> callback) {
        log.debug("→ Retry context opened for: {}", context.getAttribute("context.name"));
        return true;   // allow retry to proceed
    }

    @Override
    public <T, E extends Throwable> void close(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable == null) {
            log.debug("← Retry context closed successfully after {} attempt(s).",
                    context.getRetryCount() + 1);
        } else {
            log.error("← Retry context closed with failure after {} attempt(s). Final cause: {}",
                    context.getRetryCount() + 1, throwable.getMessage());
        }
    }
}
