package com.microservice.pro.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.microservice.pro.order_service.outbox.OutboxEvent;
import com.microservice.pro.order_service.outbox.OutboxEventRepository;
import com.microservice.pro.order_service.repository.OrderRepository;
import com.microservice.pro.order_service.event.OrderPlacedEvent;
import com.microservice.pro.order_service.idempotency.IdempotentRequest;
import com.microservice.pro.order_service.idempotency.IdempotentRequestRepository;
import io.micrometer.tracing.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotentRequestRepository idempotentRequestRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Counter ordersCreatedCounter;
    private final Tracer tracer;

    public OrderService(PaymentClient paymentClient, InventoryClient inventoryClient,
                        OrderEventPublisher orderEventPublisher, OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper,
                        KafkaTemplate<String, Object> kafkaTemplate) {
        this(paymentClient, inventoryClient, orderEventPublisher, orderRepository,
             outboxEventRepository, null, objectMapper, kafkaTemplate, new SimpleMeterRegistry(), null);
    }

    public OrderService(PaymentClient paymentClient, InventoryClient inventoryClient,
                        OrderEventPublisher orderEventPublisher, OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        IdempotentRequestRepository idempotentRequestRepository,
                        ObjectMapper objectMapper,
                        KafkaTemplate<String, Object> kafkaTemplate) {
        this(paymentClient, inventoryClient, orderEventPublisher, orderRepository,
             outboxEventRepository, idempotentRequestRepository, objectMapper, kafkaTemplate, new SimpleMeterRegistry(), null);
    }

    @Autowired
    public OrderService(PaymentClient paymentClient, InventoryClient inventoryClient,
                        OrderEventPublisher orderEventPublisher, OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        @Autowired(required = false) IdempotentRequestRepository idempotentRequestRepository,
                        ObjectMapper objectMapper,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        MeterRegistry meterRegistry,
                        @Autowired(required = false) Tracer tracer) {
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.orderEventPublisher = orderEventPublisher;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotentRequestRepository = idempotentRequestRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
        this.ordersCreatedCounter = Counter.builder("orders.created")
                .description("Total number of orders created successfully")
                .register(meterRegistry);
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
                    ordersCreatedCounter.increment();
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
     * Creates an order synchronously and writes an {@link OutboxEvent} to start the Saga.
     *
     * <h3>Outbox Pattern — Session 22 / Lab 18</h3>
     * <p>The {@code @Transactional} annotation ensures that both the {@code Order} row
     * and the {@code OutboxEvent} row are written in a <strong>single ACID transaction</strong>.
     * If either write fails, both are rolled back — the dual-write race condition is eliminated.
     *
     * <p><strong>Before (broken):</strong>
     * <pre>
     *   orderRepository.save(order);         // DB txn committed
     *   kafkaTemplate.send(...);              // if this throws → order stuck PENDING forever
     * </pre>
     *
     * <p><strong>After (fixed):</strong>
     * <pre>
     *   // Same transaction:
     *   orderRepository.save(order);         // writes to DB
     *   outboxEventRepository.save(outbox);  // writes to DB (same txn)
     *   // txn committed atomically ↑
     *   // OutboxEventRelay publishes to Kafka asynchronously, with retries
     * </pre>
     */
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        return createOrder(request, null);
    }

    /**
     * Creates an order with Idempotency Key validation and Outbox Pattern persistence.
     *
     * <h3>Idempotency Pattern (Session 22 / Lab 18 Tech Debt)</h3>
     * <p>If the client supplies an {@code Idempotency-Key} header and a matching record is
     * found in the database, the cached {@link OrderResponse} is returned immediately.
     * No duplicate order row, stock deduction, or Kafka event is produced.
     *
     * <h3>Distributed Tracing Context Propagation</h3>
     * <p>Active Zipkin/W3C trace context is extracted via Micrometer {@link Tracer} and
     * persisted in the {@link OutboxEvent} record so the background relay publishes to
     * Kafka with unbroken distributed traces.
     */
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String idempotencyKey) {
        // Step 0: Check idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank() && idempotentRequestRepository != null) {
            String trimmedKey = idempotencyKey.trim();
            java.util.Optional<IdempotentRequest> existing = idempotentRequestRepository.findByIdempotencyKey(trimmedKey);
            if (existing.isPresent()) {
                logger.info("IDEMPOTENCY: Duplicate request detected for key '{}'. Returning cached response for order ID: {}",
                        trimmedKey, existing.get().getOrderId());
                try {
                    return objectMapper.readValue(existing.get().getResponsePayload(), OrderResponse.class);
                } catch (JsonProcessingException e) {
                    logger.warn("IDEMPOTENCY: Failed to deserialize cached response payload, returning standard response: {}", e.getMessage());
                    return new OrderResponse(existing.get().getOrderId(), "PENDING", "Order placed successfully. Processing payment...");
                }
            }
        }

        String orderId = UUID.randomUUID().toString();
        logger.info("SAGA: Initiating order creation. Order ID: {}, Product ID: {}, Quantity: {}, Amount: {}",
                orderId, request.getProductId(), request.getQuantity(), request.getAmount());

        try {
            // Step 1: Sync pre-check stock (outside main txn is fine — read-only)
            StockCheckResponse stockResponse = inventoryClient.checkStock(request.getProductId(), request.getQuantity());
            if (stockResponse == null || !stockResponse.available()) {
                logger.warn("SAGA: Insufficient stock on pre-check. Rejecting order ID: {}", orderId);
                return new OrderResponse(orderId, "REJECTED", "Insufficient stock");
            }
        } catch (Exception ex) {
            logger.error("SAGA: Stock check failed or inventory service is down: {}", ex.getMessage());
            return new OrderResponse(orderId, "REJECTED", "Inventory check failed: " + ex.getMessage());
        }

        // Step 2: Save order as PENDING
        Order order = new Order(
                orderId,
                request.getProductId(),
                request.getQuantity(),
                request.getAmount(),
                OrderStatus.PENDING
        );
        orderRepository.save(order);
        ordersCreatedCounter.increment();
        logger.info("SAGA: Saved order {} to database with status PENDING", orderId);

        // Step 3: Capture distributed tracing context (traceId, spanId)
        String traceId = null;
        String spanId = null;
        try {
            if (tracer != null && tracer.currentSpan() != null) {
                traceId = tracer.currentSpan().context().traceId();
                spanId = tracer.currentSpan().context().spanId();
                logger.info("SAGA: Captured distributed trace context: traceId={}, spanId={}", traceId, spanId);
            }
        } catch (Exception ex) {
            logger.debug("SAGA: Tracing context not active: {}", ex.getMessage());
        }

        // Step 4: Write Outbox event in the SAME transaction (replaces direct kafkaTemplate.send)
        try {
            OrderPlacedEvent event = new OrderPlacedEvent(
                    orderId,
                    request.getProductId(),
                    request.getQuantity(),
                    request.getAmount(),
                    "CUSTOMER-001"
            );
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "order-events",
                    orderId,
                    payload,
                    OrderPlacedEvent.class.getName(),
                    traceId,
                    spanId
            );
            outboxEventRepository.save(outboxEvent);
            logger.info("SAGA: Wrote OutboxEvent for order {} with traceId={} — relay will publish to Kafka.", orderId, traceId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OrderPlacedEvent for outbox: " + ex.getMessage(), ex);
        }

        OrderResponse response = new OrderResponse(orderId, "PENDING", "Order placed successfully. Processing payment...");

        // Step 5: Save Idempotency record atomically in the SAME transaction
        if (idempotencyKey != null && !idempotencyKey.isBlank() && idempotentRequestRepository != null) {
            try {
                String responsePayload = objectMapper.writeValueAsString(response);
                IdempotentRequest idempotentRecord = new IdempotentRequest(idempotencyKey.trim(), orderId, responsePayload);
                idempotentRequestRepository.save(idempotentRecord);
                logger.info("IDEMPOTENCY: Persisted idempotency key '{}' mapped to order ID: {}", idempotencyKey.trim(), orderId);
            } catch (Exception ex) {
                logger.warn("IDEMPOTENCY: Failed to persist idempotency key record for key '{}': {}", idempotencyKey, ex.getMessage());
            }
        }

        return response;
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
