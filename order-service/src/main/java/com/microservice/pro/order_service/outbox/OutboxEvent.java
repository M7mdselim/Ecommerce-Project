package com.microservice.pro.order_service.outbox;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * OutboxEvent — Transactional Outbox Pattern (Session 22 / Lab 18).
 *
 * <h2>Why this entity exists — The Dual-Write Problem</h2>
 * <p>
 * Before the Outbox Pattern was introduced, {@code OrderService.createOrder()}
 * performed two independent operations:
 * <ol>
 *   <li>Save the {@code Order} to the database ({@code orderRepository.save(order)})</li>
 *   <li>Publish an {@code OrderPlacedEvent} to Kafka ({@code kafkaTemplate.send(...)})</li>
 * </ol>
 * <p>
 * These two operations have <strong>no shared transaction</strong>. If the JVM crashed,
 * the network timed out, or Kafka was temporarily unavailable between steps 1 and 2,
 * the result was a permanently stuck order:
 * <ul>
 *   <li>The order exists in the database with status {@code PENDING}.</li>
 *   <li>No event was published — the Saga <em>never starts</em>.</li>
 *   <li>The order can never progress to {@code CONFIRMED} or {@code CANCELLED}.</li>
 *   <li>No alert is generated and the customer sees a hung order indefinitely.</li>
 * </ul>
 *
 * <h2>How the Outbox fixes it</h2>
 * <p>
 * Instead of publishing directly to Kafka, {@code OrderService} now writes an
 * {@code OutboxEvent} row <strong>in the same ACID transaction</strong> as the Order row.
 * Both writes either succeed together or fail together — the dual-write race is eliminated.
 * <p>
 * A separate {@link OutboxEventRelay} process polls for {@code status = PENDING} outbox
 * rows and publishes them to Kafka. If Kafka is down, the relay retries.
 * Once published, the row is marked {@code PUBLISHED}. No saga event is ever lost.
 *
 * <h2>Failure mode comparison</h2>
 * <pre>
 * BEFORE (dual-write):
 *   DB write ──► [crash/timeout] ──► Kafka publish NEVER HAPPENS ──► Order stuck PENDING forever
 *
 * AFTER (Outbox):
 *   DB write + Outbox write (same txn) ──► relay polls ──► Kafka publish
 *   If relay crashes ──► next relay poll re-publishes (idempotent consumer handles duplicates)
 * </pre>
 */
@Entity
@Table(name = "outbox_events",
       indexes = {
           @Index(name = "idx_outbox_status", columnList = "status"),
           @Index(name = "idx_outbox_created_at", columnList = "createdAt")
       })
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The Kafka topic the relay should publish to. */
    @Column(nullable = false, length = 128)
    private String topic;

    /** The Kafka message key (orderId) — ensures correct partition routing. */
    @Column(nullable = false, length = 128)
    private String messageKey;

    /** The serialized JSON payload of the event (e.g., {@code OrderPlacedEvent}). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** The fully-qualified event class name — helps consumers deserialize correctly. */
    @Column(nullable = false, length = 256)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutboxStatus status = OutboxStatus.PENDING;

    /** Wall-clock time the row was inserted — used for ordering and debugging. */
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** Populated by the relay after successful Kafka publish. */
    private Instant publishedAt;

    /** Number of relay publish attempts — for alerting on stuck events. */
    @Column(nullable = false)
    private int retryCount = 0;

    /** Distributed tracing Trace ID (Micrometer / Brave / W3C) captured at order creation. */
    @Column(length = 64)
    private String traceId;

    /** Distributed tracing Span ID captured at order creation. */
    @Column(length = 64)
    private String spanId;

    protected OutboxEvent() {}

    public OutboxEvent(String topic, String messageKey, String payload, String eventType) {
        this(topic, messageKey, payload, eventType, null, null);
    }

    public OutboxEvent(String topic, String messageKey, String payload, String eventType, String traceId, String spanId) {
        this.topic      = topic;
        this.messageKey = messageKey;
        this.payload    = payload;
        this.eventType  = eventType;
        this.traceId    = traceId;
        this.spanId     = spanId;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String getId()           { return id; }
    public String getTopic()        { return topic; }
    public String getMessageKey()   { return messageKey; }
    public String getPayload()      { return payload; }
    public String getEventType()    { return eventType; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt()   { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getRetryCount()      { return retryCount; }
    public String getTraceId()      { return traceId; }
    public String getSpanId()       { return spanId; }

    /** Called by the relay when the message is successfully delivered to Kafka. */
    public void markPublished() {
        this.status      = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /** Called by the relay on each failed publish attempt. */
    public void incrementRetry() {
        this.retryCount++;
    }

    public enum OutboxStatus {
        /** Waiting to be picked up by the relay. */
        PENDING,
        /** Successfully published to Kafka by the relay. */
        PUBLISHED
    }
}
