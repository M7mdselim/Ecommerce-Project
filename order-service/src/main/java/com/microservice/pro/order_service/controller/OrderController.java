package com.microservice.pro.order_service.controller;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * OrderController exposes REST endpoints for managing client orders.
 * 
 * Why it exists:
 * Serves as the entry point for order creation, handling JSON payloads and mapping them
 * to the Saga pattern orchestration.
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
     * Creates an order and initiates the Choreography Saga.
     * 
     * @param request containing product ID, quantity, and payment amount
     * @return OrderResponse containing order ID and PENDING status
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.ok(response);
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
