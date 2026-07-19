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
import com.microservice.pro.order_service.entity.Order;
import com.microservice.pro.order_service.entity.OrderStatus;
import com.microservice.pro.order_service.repository.OrderRepository;
import com.microservice.pro.order_service.event.OrderPlacedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

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
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Constructor injection
    public OrderService(PaymentClient paymentClient, InventoryClient inventoryClient, 
                        OrderEventPublisher orderEventPublisher, OrderRepository orderRepository,
                        KafkaTemplate<String, Object> kafkaTemplate) {
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.orderEventPublisher = orderEventPublisher;
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
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

    /**
     * Creates an order synchronously and publishes an OrderPlacedEvent to start the Saga.
     */
    public OrderResponse createOrder(OrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        logger.info("SAGA: Initiating order creation. Order ID: {}, Product ID: {}, Quantity: {}, Amount: {}",
                orderId, request.getProductId(), request.getQuantity(), request.getAmount());

        try {
            // Step 1: Sync pre-check stock
            StockCheckResponse stockResponse = inventoryClient.checkStock(request.getProductId(), request.getQuantity());
            if (stockResponse == null || !stockResponse.available()) {
                logger.warn("SAGA: Insufficient stock on pre-check. Rejecting order ID: {}", orderId);
                return new OrderResponse(orderId, "REJECTED", "Insufficient stock");
            }
        } catch (Exception ex) {
            logger.error("SAGA: Stock check failed or inventory service is down: {}", ex.getMessage());
            return new OrderResponse(orderId, "REJECTED", "Inventory check failed: " + ex.getMessage());
        }

        // Save order as PENDING
        Order order = new Order(
                orderId,
                request.getProductId(),
                request.getQuantity(),
                request.getAmount(),
                OrderStatus.PENDING
        );
        orderRepository.save(order);
        logger.info("SAGA: Saved order {} to database with status PENDING", orderId);

        // Publish OrderPlacedEvent to start Choreography Saga
        try {
            OrderPlacedEvent event = new OrderPlacedEvent(
                    orderId,
                    request.getProductId(),
                    request.getQuantity(),
                    request.getAmount(),
                    "CUSTOMER-001" // Hardcoded customerId for lab demonstration
            );
            kafkaTemplate.send("order-events", orderId, event);
            logger.info("SAGA: Published OrderPlacedEvent for order ID: {}", orderId);
        } catch (Exception ex) {
            logger.error("SAGA: Failed to publish OrderPlacedEvent: {}", ex.getMessage());
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);
            return new OrderResponse(orderId, "FAILED", "Event publishing failed: " + ex.getMessage());
        }

        return new OrderResponse(orderId, "PENDING", "Order placed successfully. Processing payment...");
    }

    /**
     * Retrieves the status of an existing order.
     */
    public String getOrderStatus(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> order.getStatus().name())
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }
}
