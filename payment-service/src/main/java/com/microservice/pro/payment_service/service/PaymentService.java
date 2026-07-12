package com.microservice.pro.payment_service.service;

import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
<<<<<<< HEAD
=======
import org.springframework.beans.factory.annotation.Value;
>>>>>>> Task-5
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    private final Random random = new Random();

<<<<<<< HEAD
    public PaymentResponse processPayment(PaymentRequest request) {
        logger.info("Processing payment for Order: {}, Amount: {}", request.getOrderId(), request.getAmount());

        // Simulate 50% failure rate
        if (random.nextBoolean()) {
=======
    @Value("${payment.failure-rate:0.5}")
    private double failureRate;

    @Value("${payment.delay-ms:0}")
    private long delayMs;

    public PaymentResponse processPayment(PaymentRequest request) {
        logger.info("Processing payment for Order: {}, Amount: {}, Configured failureRate: {}, delayMs: {}", 
                request.getOrderId(), request.getAmount(), failureRate, delayMs);

        // Sleep before processing to simulate a slow service
        if (delayMs > 0) {
            try {
                logger.info("Simulating delay of {} ms for Order: {}", delayMs, request.getOrderId());
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Payment processing interrupted", e);
            }
        }

        // Simulate failure based on failure-rate
        if (random.nextDouble() < failureRate) {
>>>>>>> Task-5
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
