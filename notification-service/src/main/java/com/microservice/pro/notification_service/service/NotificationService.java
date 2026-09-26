package com.microservice.pro.notification_service.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

/**
 * NotificationService — Session 13.
 *
 * Listens on "payment-events" (and optionally "order-events") for status outcomes
 * and simulates sending customer notifications (Email / SMS).
 *
 * Production resilience pattern — @RetryableTopic:
 *   If notification processing encounters a temporary failure (e.g., mail gateway unreachable),
 *   Spring Kafka automatically re-routes the message to dedicated intermediate retry topics:
 *     - payment-events-retry-0 (initial backoff delay = 1000ms)
 *     - payment-events-retry-1 (delay = 2000ms, multiplier = 2.0)
 *     - payment-events-dlt     (Dead Letter Topic for manual inspection)
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            autoCreateTopics = "true"
    )
    @KafkaListener(topics = {"payment-events", "saga-results"}, groupId = "notification-service")
    public void handlePaymentOrSagaEvent(ConsumerRecord<String, String> record) {
        String payload = record.value();
        String orderId = record.key();

        log.info("[NOTIFICATION] Received event on topic '{}' for orderId: {}", record.topic(), orderId);

        if (payload != null && (payload.contains("PaymentCompleted") || payload.contains("\"success\":true"))) {
            sendOrderConfirmation(orderId);
        } else if (payload != null && (payload.contains("PaymentFailed") || payload.contains("\"success\":false"))) {
            sendPaymentFailureAlert(orderId);
        } else {
            log.debug("[NOTIFICATION] Unhandled notification payload for order: {}", orderId);
        }
    }

    @DltHandler
    public void handleDeadLetter(ConsumerRecord<String, String> record) {
        log.error("[NOTIFICATION] [DLT] Message exhausted all retries for order: {} on topic: {} — payload: {}",
                record.key(), record.topic(), record.value());
    }

    public void sendOrderConfirmation(String orderId) {
        log.info("[NOTIFICATION] ✉ Order confirmation notification sent for order: {}", orderId);
    }

    public void sendPaymentFailureAlert(String orderId) {
        log.warn("[NOTIFICATION] ⚠ Payment failure notification sent for order: {}", orderId);
    }
}
