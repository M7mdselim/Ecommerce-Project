package com.microservice.pro.order_service.listener;

<<<<<<< HEAD
import io.github.resilience4j.retry.RetryRegistry;
=======
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
>>>>>>> Task-5
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class RetryLogger {

    private static final Logger logger = LoggerFactory.getLogger(RetryLogger.class);
    private final RetryRegistry retryRegistry;
<<<<<<< HEAD

    public RetryLogger(RetryRegistry retryRegistry) {
        this.retryRegistry = retryRegistry;
=======
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;

    public RetryLogger(RetryRegistry retryRegistry,
                       CircuitBreakerRegistry circuitBreakerRegistry,
                       BulkheadRegistry bulkheadRegistry,
                       TimeLimiterRegistry timeLimiterRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
>>>>>>> Task-5
    }

    @PostConstruct
    public void registerListeners() {
<<<<<<< HEAD
        logger.info("Registering Resilience4j Retry Event Listeners...");
=======
        logger.info("Registering Resilience4j Event Listeners...");
>>>>>>> Task-5
        
        retryRegistry.retry("paymentService").getEventPublisher()
                .onRetry(event -> logger.info("[RETRY] Attempt #{}", event.getNumberOfRetryAttempts()))
                .onSuccess(event -> logger.info("[RETRY] Success after attempt #{}", event.getNumberOfRetryAttempts()))
                .onError(event -> logger.warn("[RETRY] Failed after attempt #{}", event.getNumberOfRetryAttempts()));
<<<<<<< HEAD
=======

        circuitBreakerRegistry.circuitBreaker("paymentService").getEventPublisher()
                .onStateTransition(event -> logger.info("[CIRCUIT BREAKER] State transitioned from {} to {}", event.getStateTransition().getFromState(), event.getStateTransition().getToState()))
                .onError(event -> logger.error("[CIRCUIT BREAKER] Call failed: {}", event.getThrowable().getMessage()))
                .onSuccess(event -> logger.info("[CIRCUIT BREAKER] Call succeeded."));

        bulkheadRegistry.bulkhead("paymentService").getEventPublisher()
                .onCallPermitted(event -> logger.info("[BULKHEAD] Call permitted by bulkhead"))
                .onCallRejected(event -> logger.warn("[BULKHEAD] Call rejected. Bulkhead full."));

        timeLimiterRegistry.timeLimiter("paymentService").getEventPublisher()
                .onTimeout(event -> logger.warn("[TIMEOUT] Timeout occurred after configured duration."));
>>>>>>> Task-5
    }
}
