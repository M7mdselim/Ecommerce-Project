package com.microservice.pro.order_service.service;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.dto.PaymentRequest;
import com.microservice.pro.order_service.dto.PaymentResponse;
import com.microservice.pro.order_service.dto.StockCheckResponse;
import com.microservice.pro.order_service.exception.InsufficientStockException;
import com.microservice.pro.order_service.exception.InventoryUnavailableException;
import com.microservice.pro.order_service.exception.ProductNotFoundException;
import com.microservice.pro.order_service.event.OrderCreatedEvent;
import com.microservice.pro.order_service.messaging.OrderEventPublisher;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * OrderService manages order lifecycle events including stock checking, billing, and fulfillment.
 * 
 * Why it exists:
 * Serves as the primary coordinator for order placement, applying Resilience4j patterns
 * (bulkhead, circuit breaker, timeout, retry) across distributed calls.
 */
@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;
    private final OrderEventPublisher orderEventPublisher;

    // Constructor injection for both Feign Clients and the Event Publisher
    public OrderService(PaymentClient paymentClient, InventoryClient inventoryClient, OrderEventPublisher orderEventPublisher) {
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.orderEventPublisher = orderEventPublisher;
    }

    /**
     * Creates an order asynchronously.
     * Integrates an inventory check via OpenFeign before initiating payment.
     * 
     * @param request the order details
     * @param requestAttributes parent thread HTTP context attributes for header propagation
     * @return a CompletableFuture wrapping the OrderResponse
     */
    @Bulkhead(name = "paymentService", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "bulkheadFallback")
    @TimeLimiter(name = "paymentService", fallbackMethod = "timeLimiterFallback")
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public CompletableFuture<OrderResponse> createOrderAsync(OrderRequest request, RequestAttributes requestAttributes) {
        return CompletableFuture.supplyAsync(() -> {
            String orderId = UUID.randomUUID().toString();
            logger.info("Initiating order creation. Order ID: {}, Product ID: {}, Quantity: {}, Amount: {}",
                    orderId, request.getProductId(), request.getQuantity(), request.getAmount());

            // Propigate request context to the async thread pool
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }

            try {
                // Step 1: Inventory Check
                logger.info("[INVENTORY] Step 1: Checking stock for Product ID: {} (Quantity: {})", request.getProductId(), request.getQuantity());
                StockCheckResponse stockResponse = inventoryClient.checkStock(request.getProductId(), request.getQuantity());
                logger.info("[INVENTORY] Response received: {}", stockResponse);

                if (stockResponse == null || !stockResponse.available()) {
                    logger.warn("[INVENTORY] Insufficient stock. Rejecting order ID: {}", orderId);
                    return new OrderResponse(orderId, "REJECTED", "Insufficient stock");
                }

                // Step 2: Payment Processing
                PaymentRequest paymentRequest = new PaymentRequest(orderId, request.getAmount());
                logger.info("[PAYMENT] Step 2: Initiating payment for Order ID: {} (Amount: {})", orderId, request.getAmount());
                PaymentResponse paymentResponse = paymentClient.processPayment(paymentRequest);
                logger.info("[PAYMENT] Response received: Status={}, TransactionId={}", 
                        paymentResponse.getStatus(), paymentResponse.getTransactionId());

                if ("APPROVED".equalsIgnoreCase(paymentResponse.getStatus())) {
                    // Extract customer ID (X-User-Id) from the request attributes context
                    String customerId = "UNKNOWN";
                    if (requestAttributes instanceof ServletRequestAttributes servletAttrs) {
                        String xUserId = servletAttrs.getRequest().getHeader("X-User-Id");
                        if (xUserId != null) {
                            customerId = xUserId;
                        }
                    }

                    // Save Order step (simulated by returning the response, as database integration is mock)
                    logger.info("[ORDER DATABASE] Order ID: {} has been successfully saved to DB", orderId);

                    // Publish the event: Kafka failure must not block the order flow
                    try {
                        OrderCreatedEvent event = new OrderCreatedEvent(orderId, customerId, request.getAmount());
                        orderEventPublisher.publishOrderCreated(event);
                    } catch (Exception ex) {
                        logger.error("Failed to publish OrderCreatedEvent for order: {}. Error: {}", orderId, ex.getMessage(), ex);
                    }

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

            } catch (InsufficientStockException ex) {
                logger.warn("[INVENTORY ERROR] Insufficient stock. Order: {}. Reason: {}", orderId, ex.getMessage());
                return new OrderResponse(orderId, "REJECTED", "Insufficient stock");
            } catch (ProductNotFoundException ex) {
                logger.error("[INVENTORY ERROR] Product not found. Order: {}. Reason: {}", orderId, ex.getMessage());
                return new OrderResponse(orderId, "REJECTED", "Product not found");
            } catch (InventoryUnavailableException ex) {
                logger.error("[INVENTORY ERROR] Inventory service unavailable. Order: {}. Reason: {}", orderId, ex.getMessage());
                return new OrderResponse(orderId, "PENDING", "Inventory service unavailable");
            } finally {
                // Clean up request attributes context from the thread
                RequestContextHolder.resetRequestAttributes();
            }
        });
    }

    /**
     * Fallback method when Semaphore Bulkhead limit is exceeded.
     */
    public CompletableFuture<OrderResponse> bulkheadFallback(OrderRequest request, RequestAttributes requestAttributes, Throwable ex) {
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
    public CompletableFuture<OrderResponse> timeLimiterFallback(OrderRequest request, RequestAttributes requestAttributes, Throwable ex) {
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
    public CompletableFuture<OrderResponse> paymentFallback(OrderRequest request, RequestAttributes requestAttributes, Throwable ex) {
        logger.error("Payment fallback triggered. Error message: {}", ex.getMessage());
        return CompletableFuture.completedFuture(new OrderResponse(
                null,
                "PENDING",
                "Payment unavailable."
        ));
    }
}
