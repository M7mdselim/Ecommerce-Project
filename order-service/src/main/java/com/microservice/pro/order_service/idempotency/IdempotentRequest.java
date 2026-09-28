package com.microservice.pro.order_service.idempotency;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * IdempotentRequest entity — Idempotency Key Pattern (Session 22 / Lab 18 Tech Debt).
 *
 * <h2>Failure mode this solves</h2>
 * <p>
 * When a client times out waiting for an HTTP response and automatically retries
 * {@code POST /api/orders}, the server receives the request twice.
 * Without an idempotency key check, the server creates two orders, generates two
 * {@code OutboxEvent} entries, charges the customer twice, and reserves stock twice.
 *
 * <h2>Guarantees</h2>
 * <p>
 * By providing an {@code Idempotency-Key} HTTP header (e.g. UUID), the client ensures
 * that subsequent retries within the retention window will return the exact same
 * {@code OrderResponse} without executing order creation or writing duplicate outbox events.
 */
@Entity
@Table(name = "idempotent_requests",
       indexes = {
           @Index(name = "idx_idempotency_key", columnList = "idempotencyKey", unique = true),
           @Index(name = "idx_idempotency_created_at", columnList = "createdAt")
       })
public class IdempotentRequest {

    @Id
    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 128)
    private String orderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String responsePayload;

    @Column(nullable = false, length = 32)
    private String status = "PROCESSED";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected IdempotentRequest() {}

    public IdempotentRequest(String idempotencyKey, String orderId, String responsePayload) {
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.responsePayload = responsePayload;
        this.status = "PROCESSED";
        this.createdAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
