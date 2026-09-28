package com.microservice.pro.payment_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import com.microservice.pro.payment_service.event.PaymentCompletedEvent;
import com.microservice.pro.payment_service.event.PaymentFailedEvent;
import com.microservice.pro.payment_service.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSagaHandlerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentSagaHandler handler;

    @Test
    void handleInventoryReserved_ignoresOtherEventTypesOnTheSameTopic() {
        String otherEventJson = "{\"orderId\":\"order-123\",\"reason\":\"out of stock\"}";

        handler.handleInventoryReserved(otherEventJson);

        verify(paymentService, never()).processPayment(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void handleInventoryReserved_publishesPaymentCompleted_onSuccess() {
        String inventoryReservedJson = "{\"orderId\":\"order-123\",\"productId\":\"PROD-001\",\"quantity\":2}";
        PaymentResponse response = new PaymentResponse("APPROVED", "tx-456", BigDecimal.TEN);
        when(paymentService.processPayment(any(PaymentRequest.class))).thenReturn(response);

        handler.handleInventoryReserved(inventoryReservedJson);

        verify(kafkaTemplate).send(
                eq("payment-events"),
                eq("order-123"),
                any(PaymentCompletedEvent.class));
    }

    @Test
    void handleInventoryReserved_publishesPaymentFailed_whenServiceThrows() {
        String inventoryReservedJson = "{\"orderId\":\"order-123\",\"productId\":\"PROD-001\",\"quantity\":2}";
        when(paymentService.processPayment(any(PaymentRequest.class)))
                .thenThrow(new RuntimeException("card declined"));

        handler.handleInventoryReserved(inventoryReservedJson);

        verify(kafkaTemplate).send(
                eq("payment-events"),
                eq("order-123"),
                any(PaymentFailedEvent.class));
    }
}
