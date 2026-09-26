package com.microservice.pro.order_service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * OutboxEventRelay — Transactional Outbox Pattern relay process (Session 22 / Lab 18).
 *
 * <h2>Role in the pattern</h2>
 * <p>
 * This component is the second half of the Transactional Outbox Pattern.
 * It runs on a fixed schedule (every 5 seconds), queries the {@code outbox_events}
 * table for {@code PENDING} rows, publishes each one to Kafka, and marks the row
 * {@code PUBLISHED} — all within the same database transaction.
 *
 * <h2>At-least-once guarantee</h2>
 * <p>
 * The relay provides <strong>at-least-once</strong> delivery (not exactly-once):
 * <ul>
 *   <li>If the JVM crashes <em>after</em> Kafka publish but <em>before</em> the DB commit,
 *       the row stays {@code PENDING} and the relay will re-publish on the next cycle.</li>
 *   <li>Consumers (Inventory/Payment services) must be idempotent to handle duplicates.</li>
 * </ul>
 *
 * <h2>Failure mode the relay solves</h2>
 * <p>Before the Outbox Pattern, if Kafka was unavailable when {@code OrderService.createOrder()}
 * tried to publish, the event was lost silently. The saga never started.
 * Now the event persists in the DB, and the relay retries indefinitely until Kafka is available.
 *
 * <h2>Observability</h2>
 * <p>
 * The relay logs a warning on every failed publish attempt, making Kafka outages
 * immediately visible in the application logs / alerting system.
 * The {@code retryCount} column on each row lets ops teams identify stuck events.
 */
@Component
public class OutboxEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);

    /** Maximum events processed per tick — prevents OOM during large backlogs. */
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxEventRelay(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate         = kafkaTemplate;
    }

    /**
     * Polls for PENDING outbox events every 5 seconds and publishes them to Kafka.
     *
     * <p><strong>Transaction boundary:</strong> each event is updated in its own
     * transaction. A single bad event cannot roll back the entire batch.
     */
    @Scheduled(fixedDelay = 5_000)
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(BATCH_SIZE);

        if (pending.isEmpty()) {
            return; // Nothing to do this cycle
        }

        log.info("[OUTBOX-RELAY] Found {} PENDING event(s) to publish.", pending.size());

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    /**
     * Publishes a single outbox event to Kafka and marks it PUBLISHED.
     * Each event runs in its own transaction so a failed event does not
     * block subsequent events from being published.
     */
    @Transactional
    public void publishOne(OutboxEvent event) {
        try {
            // Synchronous send — blocks until broker acknowledges (or throws)
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                         .get(); // .get() converts the ListenableFuture to a blocking call

            event.markPublished();
            outboxEventRepository.save(event);

            log.info("[OUTBOX-RELAY] Published event id={} topic={} key={}",
                     event.getId(), event.getTopic(), event.getMessageKey());

        } catch (Exception ex) {
            event.incrementRetry();
            outboxEventRepository.save(event);

            log.warn("[OUTBOX-RELAY] Failed to publish event id={} (attempt #{}) — will retry. Error: {}",
                     event.getId(), event.getRetryCount(), ex.getMessage());
        }
    }
}
