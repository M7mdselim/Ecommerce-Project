package com.microservice.pro.order_service.service;

import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.dto.PaymentRequest;
import com.microservice.pro.order_service.dto.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OrderServiceTest {

    @Mock
    private PaymentClient paymentClient;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orderService = new OrderService(paymentClient);
    }

    @Test
    void testCreateOrderAsync_Approved() throws ExecutionException, InterruptedException {
        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));
        PaymentResponse mockResponse = new PaymentResponse("APPROVED", "tx123", new BigDecimal("100.00"));
        
        when(paymentClient.processPayment(any(PaymentRequest.class))).thenReturn(mockResponse);

        CompletableFuture<OrderResponse> responseFuture = orderService.createOrderAsync(request);
        OrderResponse response = responseFuture.get();

        assertNotNull(response);
        assertEquals("CONFIRMED", response.getStatus());
        assertTrue(response.getMessage().contains("tx123"));
        verify(paymentClient, times(1)).processPayment(any(PaymentRequest.class));
    }

    @Test
    void testCreateOrderAsync_Rejected() throws ExecutionException, InterruptedException {
        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));
        PaymentResponse mockResponse = new PaymentResponse("REJECTED", "tx123", new BigDecimal("100.00"));
        
        when(paymentClient.processPayment(any(PaymentRequest.class))).thenReturn(mockResponse);

        CompletableFuture<OrderResponse> responseFuture = orderService.createOrderAsync(request);
        OrderResponse response = responseFuture.get();

        assertNotNull(response);
        assertEquals("FAILED", response.getStatus());
        assertEquals("Payment was not approved.", response.getMessage());
    }
}
