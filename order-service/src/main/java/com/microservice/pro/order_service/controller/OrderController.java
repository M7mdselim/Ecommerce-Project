package com.microservice.pro.order_service.controller;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.CompletableFuture;

/**
 * OrderController exposes REST endpoints for managing client orders.
 * 
 * Why it exists:
 * Serves as the entry point for order creation, handling JSON payloads and mapping them
 * to the Saga pattern orchestration with Idempotency Key validation.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    // Constructor injection of the OrderService bean
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Creates an order and initiates the Choreography Saga with Idempotency Key support.
     * 
     * @param idempotencyKey optional unique UUID header to prevent duplicate order placement
     * @param request containing product ID, quantity, and payment amount
     * @return OrderResponse containing order ID and PENDING status
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.ok(response);
    }

    /**
     * Creates an order asynchronously, applying Resilience4j bulkhead, timelimiter,
     * circuit breaker, and retry patterns.
     * 
     * @param request containing product ID, quantity, and payment amount
     * @return a CompletableFuture wrapping the OrderResponse inside a ResponseEntity
     */
    @PostMapping("/async")
    public CompletableFuture<ResponseEntity<OrderResponse>> createOrderAsync(@RequestBody OrderRequest request) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        return orderService.createOrderAsync(request, requestAttributes)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Retrieves the status of an existing order by its ID.
     * 
     * @param orderId the order ID
     * @return the string representing the order status
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<String> getOrderStatus(@PathVariable String orderId) {
        String status = orderService.getOrderStatus(orderId);
        return ResponseEntity.ok(status);
    }
}
