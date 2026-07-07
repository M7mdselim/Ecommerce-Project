package com.microservice.pro.order_service.service;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.dto.PaymentRequest;
import com.microservice.pro.order_service.dto.PaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient paymentClient;

    public OrderService(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    /**
     * Creates an order and initiates payment processing.
     * Protected by Circuit Breaker and Retry aspects.
     * Execution order: Circuit Breaker -> Retry -> Payment Client.
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public OrderResponse createOrder(OrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        logger.info("Initiating order creation. Order ID: {}, Product ID: {}, Quantity: {}, Amount: {}",
                orderId, request.getProductId(), request.getQuantity(), request.getAmount());

        PaymentRequest paymentRequest = new PaymentRequest(orderId, request.getAmount());
        
        logger.info("Calling payment service for Order ID: {}", orderId);
        PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest);

        logger.info("Payment service responded: Status={}, TransactionId={}", 
                paymentResponse.getStatus(), paymentResponse.getTransactionId());

        if ("APPROVED".equalsIgnoreCase(paymentResponse.getStatus())) {
            return new OrderResponse(
                    orderId,
                    "CONFIRMED",
                    "Order placed successfully. Transaction ID: " + paymentResponse.getTransactionId()
            );
        } else {
            return new OrderResponse(
                    orderId,
                    "FAILED",
                    "Payment was not approved."
            );
        }
    }

    /**
     * Fallback method when Payment Service fails repeatedly or the Circuit Breaker is OPEN.
     * Signature must match original method with an extra Throwable argument at the end.
     */
    public OrderResponse paymentFallback(OrderRequest request, Throwable ex) {
        logger.error("Payment fallback triggered. Error message: {}", ex.getMessage());
        return new OrderResponse(
                null,
                "PENDING",
                "Payment service unavailable. Order will be processed later."
        );
    }
}
