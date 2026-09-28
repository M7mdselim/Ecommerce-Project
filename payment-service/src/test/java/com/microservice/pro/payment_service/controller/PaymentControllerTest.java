package com.microservice.pro.payment_service.controller;

import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import com.microservice.pro.payment_service.idempotency.IdempotencyRecord;
import com.microservice.pro.payment_service.idempotency.IdempotencyRepository;
import com.microservice.pro.payment_service.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @InjectMocks
    private PaymentController paymentController;

    private PaymentRequest request;

    @BeforeEach
    void setUp() {
        request = new PaymentRequest("order-123", new BigDecimal("100.00"));
    }

    @Test
    void processPayment_whenNoIdempotencyKey_processesNormally() {
        PaymentResponse response = new PaymentResponse("APPROVED", "tx-999", new BigDecimal("100.00"));
        when(paymentService.processPayment(request)).thenReturn(response);

        ResponseEntity<PaymentResponse> result = paymentController.processPayment(request, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getTransactionId()).isEqualTo("tx-999");
        verifyNoInteractions(idempotencyRepository);
    }

    @Test
    void processPayment_whenNewIdempotencyKey_recordsAndProcesses() {
        String key = "idemp-001";
        PaymentResponse response = new PaymentResponse("APPROVED", "tx-100", new BigDecimal("100.00"));
        when(idempotencyRepository.findById(key)).thenReturn(Optional.empty());
        when(paymentService.processPayment(request)).thenReturn(response);

        IdempotencyRecord record = new IdempotencyRecord(key, "order-123");
        when(idempotencyRepository.findById(key)).thenReturn(Optional.empty(), Optional.of(record));

        ResponseEntity<PaymentResponse> result = paymentController.processPayment(request, key);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getTransactionId()).isEqualTo("tx-100");
        verify(idempotencyRepository, times(2)).save(any(IdempotencyRecord.class));
    }

    @Test
    void processPayment_whenDuplicateCompletedKey_returnsCachedResponseWithoutCallingService() {
        String key = "idemp-002";
        IdempotencyRecord record = new IdempotencyRecord(key, "order-123");
        record.complete("cached-tx-777");
        when(idempotencyRepository.findById(key)).thenReturn(Optional.of(record));

        ResponseEntity<PaymentResponse> result = paymentController.processPayment(request, key);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getBody().getTransactionId()).isEqualTo("cached-tx-777");
        verify(paymentService, never()).processPayment(any());
    }

    @Test
    void processPayment_whenDuplicateProcessingKey_returnsAccepted202() {
        String key = "idemp-003";
        IdempotencyRecord record = new IdempotencyRecord(key, "order-123"); // status is PROCESSING
        when(idempotencyRepository.findById(key)).thenReturn(Optional.of(record));

        ResponseEntity<PaymentResponse> result = paymentController.processPayment(request, key);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody().getStatus()).isEqualTo("PROCESSING");
        verify(paymentService, never()).processPayment(any());
    }
}
