package com.microservice.pro.order_service.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OutboxEventRelay} — Lab 18 (Session 22).
 *
 * <p>These tests demonstrate the core logic of the Outbox Pattern:
 * <ol>
 *   <li>A PENDING event is published to Kafka and then marked PUBLISHED in the DB.</li>
 *   <li>When Kafka is unavailable, the event stays PENDING and retry count increments,
 *       so the relay will try again on the next schedule tick.</li>
 * </ol>
 *
 * <p>Together these two tests prove that the dual-write failure mode is fixed:
 * the relay guarantees at-least-once delivery even when Kafka is temporarily unavailable.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @InjectMocks
    private OutboxEventRelay relay;

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — Happy path: event is published to Kafka and marked PUBLISHED
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the core Outbox guarantee:
     * when Kafka is available, the relay publishes the event and marks it
     * {@code PUBLISHED} in the same transaction — so it will not be re-sent.
     *
     * <p><strong>Failure mode this test proves is fixed:</strong>
     * Before the Outbox Pattern, a crash between {@code orderRepository.save()} and
     * {@code kafkaTemplate.send()} left the order stuck {@code PENDING} forever.
     * Now the relay retries until Kafka succeeds, then atomically marks it done.
     */
    @Test
    @DisplayName("relay: PENDING event is published to Kafka and marked PUBLISHED")
    @SuppressWarnings("unchecked")
    void relay_whenKafkaAvailable_publishesEventAndMarksPublished() {
        // ── Arrange ──────────────────────────────────────────────────────────
        OutboxEvent event = new OutboxEvent(
                "order-events",
                "order-123",
                "{\"orderId\":\"order-123\",\"productId\":\"PROD-001\"}",
                "com.microservice.pro.order_service.event.OrderPlacedEvent"
        );

        // Kafka template returns a successful future
        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(
                mock(SendResult.class)
        );
        given(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .willReturn(successFuture);
        given(outboxEventRepository.findPendingEvents(anyInt()))
                .willReturn(List.of(event));

        // ── Act ───────────────────────────────────────────────────────────────
        relay.relay();

        // ── Assert ────────────────────────────────────────────────────────────
        // 1. Kafka received the event on the correct topic with the correct key
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());

        assertThat(recordCaptor.getValue().topic()).isEqualTo("order-events");
        assertThat(recordCaptor.getValue().key()).isEqualTo("order-123");
        assertThat(recordCaptor.getValue().value()).contains("PROD-001");

        // 2. The event row is saved with PUBLISHED status (not still PENDING)
        ArgumentCaptor<OutboxEvent> savedCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus())
                .isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(savedCaptor.getValue().getPublishedAt()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — Kafka down: event stays PENDING, retry count increments
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the retry guarantee:
     * when Kafka publish throws, the event remains {@code PENDING} and
     * {@code retryCount} increments — so the next relay tick will try again
     * without losing the event.
     *
     * <p><strong>This is impossible with the old dual-write approach:</strong>
     * the old code caught the exception and set the order to {@code CANCELLED} immediately.
     * With the Outbox, a temporary Kafka outage simply means "try again in 5 seconds".
     */
    @Test
    @DisplayName("relay: Kafka failure leaves event PENDING and increments retryCount")
    @SuppressWarnings("unchecked")
    void relay_whenKafkaUnavailable_keepsEventPendingAndIncrementsRetry() {
        // ── Arrange ──────────────────────────────────────────────────────────
        OutboxEvent event = new OutboxEvent(
                "order-events",
                "order-456",
                "{\"orderId\":\"order-456\"}",
                "com.microservice.pro.order_service.event.OrderPlacedEvent"
        );
        assertThat(event.getRetryCount()).isZero(); // starts at 0

        // Kafka throws a runtime exception (broker unreachable)
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker not available"));
        given(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .willReturn(failedFuture);
        given(outboxEventRepository.findPendingEvents(anyInt()))
                .willReturn(List.of(event));

        // ── Act ───────────────────────────────────────────────────────────────
        relay.relay();

        // ── Assert ────────────────────────────────────────────────────────────
        // 1. Event status is still PENDING (NOT cancelled, NOT lost)
        assertThat(event.getStatus())
                .as("Event must remain PENDING when Kafka is unavailable")
                .isEqualTo(OutboxEvent.OutboxStatus.PENDING);

        // 2. retryCount incremented so ops can detect stuck events
        assertThat(event.getRetryCount())
                .as("retryCount must increment to track failed attempts")
                .isEqualTo(1);

        // 3. The row is saved (to persist the incremented retry count)
        verify(outboxEventRepository).save(event);

        // 4. publishedAt is still null (event was NOT marked published)
        assertThat(event.getPublishedAt())
                .as("publishedAt must be null when publish failed")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Distributed Tracing Context Propagation across Outbox to Kafka
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("relay: propagates traceId and spanId as B3 and W3C traceparent headers to Kafka")
    @SuppressWarnings("unchecked")
    void relay_whenTraceContextPresent_injectsTracingHeadersIntoKafkaRecord() {
        // ── Arrange ──────────────────────────────────────────────────────────
        OutboxEvent event = new OutboxEvent(
                "order-events",
                "order-789",
                "{\"orderId\":\"order-789\"}",
                "com.microservice.pro.order_service.event.OrderPlacedEvent",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7"
        );

        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(
                mock(SendResult.class)
        );
        given(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .willReturn(successFuture);
        given(outboxEventRepository.findPendingEvents(anyInt()))
                .willReturn(List.of(event));

        // ── Act ───────────────────────────────────────────────────────────────
        relay.relay();

        // ── Assert ────────────────────────────────────────────────────────────
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        org.apache.kafka.clients.producer.ProducerRecord<String, String> record = recordCaptor.getValue();

        assertThat(record.headers().lastHeader("X-B3-TraceId")).isNotNull();
        assertThat(new String(record.headers().lastHeader("X-B3-TraceId").value()))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(record.headers().lastHeader("X-B3-SpanId")).isNotNull();
        assertThat(new String(record.headers().lastHeader("X-B3-SpanId").value()))
                .isEqualTo("00f067aa0ba902b7");

        assertThat(record.headers().lastHeader("traceparent")).isNotNull();
        assertThat(new String(record.headers().lastHeader("traceparent").value()))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — Nothing to do: relay is a no-op when outbox is empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("relay: no-op when outbox has no PENDING events")
    @SuppressWarnings("unchecked")
    void relay_whenNoPendingEvents_doesNothing() {
        given(outboxEventRepository.findPendingEvents(anyInt()))
                .willReturn(List.of());

        relay.relay();

        // Kafka must NOT be called when there are no pending events
        verifyNoInteractions(kafkaTemplate);
        // Repository must NOT be called for save (only for the initial query)
        verify(outboxEventRepository, never()).save(any());
    }
}
