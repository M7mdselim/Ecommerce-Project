package com.microservice.pro.order_service.listener;

import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class RetryLogger {

    private static final Logger logger = LoggerFactory.getLogger(RetryLogger.class);
    private final RetryRegistry retryRegistry;

    public RetryLogger(RetryRegistry retryRegistry) {
        this.retryRegistry = retryRegistry;
    }

    @PostConstruct
    public void registerListeners() {
        logger.info("Registering Resilience4j Retry Event Listeners...");
        
        retryRegistry.retry("paymentService").getEventPublisher()
                .onRetry(event -> logger.info("[RETRY] Attempt #{}", event.getNumberOfRetryAttempts()))
                .onSuccess(event -> logger.info("[RETRY] Success after attempt #{}", event.getNumberOfRetryAttempts()))
                .onError(event -> logger.warn("[RETRY] Failed after attempt #{}", event.getNumberOfRetryAttempts()));
    }
}
