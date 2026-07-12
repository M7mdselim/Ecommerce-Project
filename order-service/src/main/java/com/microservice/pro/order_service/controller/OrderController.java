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
 * to asynchronous business logic inside OrderService.
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
     * Creates an order asynchronously.
     * Captures request attributes from the main request thread to allow JWT token propagation
     * to child threads during internal Feign calls.
     * 
     * @param request containing product ID, quantity, and payment amount
     * @return a CompletableFuture wrapping the OrderResponse inside a ResponseEntity
     */
    @PostMapping
    public CompletableFuture<ResponseEntity<OrderResponse>> createOrder(@RequestBody OrderRequest request) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        return orderService.createOrderAsync(request, requestAttributes)
                .thenApply(ResponseEntity::ok);
    }
}
