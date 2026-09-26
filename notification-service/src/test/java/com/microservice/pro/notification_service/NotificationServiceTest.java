package com.microservice.pro.notification_service;

import com.microservice.pro.notification_service.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NotificationServiceTest {

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService();
    }

    @Test
    @DisplayName("Should handle PaymentCompleted event successfully")
    void testHandlePaymentCompleted() {
        String payload = "{\"orderId\":\"ORDER-123\",\"status\":\"PaymentCompleted\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ORDER-123", payload);

        assertDoesNotThrow(() -> notificationService.handlePaymentOrSagaEvent(record));
    }

    @Test
    @DisplayName("Should handle PaymentFailed event successfully")
    void testHandlePaymentFailed() {
        String payload = "{\"orderId\":\"ORDER-456\",\"status\":\"PaymentFailed\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 0, 0L, "ORDER-456", payload);

        assertDoesNotThrow(() -> notificationService.handlePaymentOrSagaEvent(record));
    }

    @Test
    @DisplayName("Should handle dead letter topic message without throwing")
    void testHandleDeadLetter() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events-dlt", 0, 0L, "ORDER-789", "invalid payload");

        assertDoesNotThrow(() -> notificationService.handleDeadLetter(record));
    }
}
