package com.microservice.pro.order_service.controller;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

<<<<<<< HEAD
=======
import java.util.concurrent.CompletableFuture;

>>>>>>> Task-5
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
<<<<<<< HEAD
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.ok(response);
=======
    public CompletableFuture<ResponseEntity<OrderResponse>> createOrder(@RequestBody OrderRequest request) {
        return orderService.createOrderAsync(request)
                .thenApply(ResponseEntity::ok);
>>>>>>> Task-5
    }
}
