package com.microservice.pro.payment_service.service;

import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    private final Random random = new Random();

    public PaymentResponse processPayment(PaymentRequest request) {
        logger.info("Processing payment for Order: {}, Amount: {}", request.getOrderId(), request.getAmount());

        // Simulate 50% failure rate
        if (random.nextBoolean()) {
            logger.warn("Simulated payment failure for Order: {}", request.getOrderId());
            throw new RuntimeException("Simulated Payment Service failure");
        }

        logger.info("Payment approved for Order: {}", request.getOrderId());
        return new PaymentResponse(
                "APPROVED",
                UUID.randomUUID().toString(),
                request.getAmount()
        );
    }
}
