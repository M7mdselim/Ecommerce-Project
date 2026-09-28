package com.microservice.pro.payment_service.controller;

import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import com.microservice.pro.payment_service.idempotency.IdempotencyRecord;
import com.microservice.pro.payment_service.idempotency.IdempotencyRepository;
import com.microservice.pro.payment_service.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/payments", "/api/v1/payments"})
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final IdempotencyRepository idempotencyRepository;

    public PaymentController(PaymentService paymentService, IdempotencyRepository idempotencyRepository) {
        this.paymentService = paymentService;
        this.idempotencyRepository = idempotencyRepository;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        // Idempotency check (Session 22 Lab 18 Task 2)
        if (idempotencyKey != null) {
            var existing = idempotencyRepository.findById(idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                log.info("[IDEMPOTENCY] Duplicate payment request for key={}, status={}", idempotencyKey, record.getStatus());
                if ("COMPLETED".equals(record.getStatus())) {
                    return ResponseEntity.ok(new PaymentResponse("COMPLETED", record.getResponsePayload(), request.getAmount()));
                }
                return ResponseEntity.accepted()
                        .body(new PaymentResponse("PROCESSING", "Payment already in progress", request.getAmount(), "Payment already in progress"));
            }
            idempotencyRepository.save(new IdempotencyRecord(idempotencyKey, request.getOrderId()));
        }

        try {
            PaymentResponse response = paymentService.processPayment(request);
            if (idempotencyKey != null) {
                idempotencyRepository.findById(idempotencyKey).ifPresent(r -> {
                    r.complete(response.getTransactionId());
                    idempotencyRepository.save(r);
                });
            }
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            if (idempotencyKey != null) {
                idempotencyRepository.findById(idempotencyKey).ifPresent(r -> {
                    r.fail(ex.getMessage());
                    idempotencyRepository.save(r);
                });
            }
            throw ex;
        }
    }
}
