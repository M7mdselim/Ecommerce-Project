package com.microservice.pro.order_service.service;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.dto.PaymentRequest;
import com.microservice.pro.order_service.dto.PaymentResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient paymentClient;

    public OrderService(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    /**
     * Creates an order and initiates payment processing asynchronously.
     * Protected by Bulkhead, TimeLimiter, Circuit Breaker, and Retry aspects.
     * Execution order: Bulkhead -> TimeLimiter -> Circuit Breaker -> Retry -> Payment Client.
     */
    @Bulkhead(name = "paymentService", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "bulkheadFallback")
    @TimeLimiter(name = "paymentService", fallbackMethod = "timeLimiterFallback")
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public CompletableFuture<OrderResponse> createOrderAsync(OrderRequest request) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    /**
     * Fallback method when Semaphore Bulkhead limit is exceeded.
     */
    public CompletableFuture<OrderResponse> bulkheadFallback(OrderRequest request, Throwable ex) {
        logger.error("[BULKHEAD] Bulkhead limit exceeded. Error message: {}", ex.getMessage());
        return CompletableFuture.completedFuture(new OrderResponse(
                null,
                "QUEUED",
                "System busy. Your order has been queued."
        ));
    }

    /**
     * Fallback method when TimeLimiter timeout limit is reached.
     */
    public CompletableFuture<OrderResponse> timeLimiterFallback(OrderRequest request, Throwable ex) {
        logger.error("[TIMEOUT] Payment timed out. Error message: {}", ex.getMessage());
        return CompletableFuture.completedFuture(new OrderResponse(
                null,
                "PENDING",
                "Payment timed out."
        ));
    }

    /**
     * Fallback method when Payment Service fails repeatedly or the Circuit Breaker is OPEN.
     */
    public CompletableFuture<OrderResponse> paymentFallback(OrderRequest request, Throwable ex) {
        logger.error("Payment fallback triggered. Error message: {}", ex.getMessage());
        return CompletableFuture.completedFuture(new OrderResponse(
                null,
                "PENDING",
                "Payment unavailable."
        ));
    }
}
