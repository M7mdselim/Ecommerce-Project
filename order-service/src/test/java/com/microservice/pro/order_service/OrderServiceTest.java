package com.microservice.pro.order_service;

import com.microservice.pro.order_service.dto.*;
import com.microservice.pro.order_service.service.InventoryClient;
import com.microservice.pro.order_service.service.OrderService;
import com.microservice.pro.order_service.service.PaymentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderService validating business flow:
 * 1. Checks inventory first.
 * 2. Proceeds to payment only if inventory is available.
 * 3. Skips payment and returns REJECTED status if stock is insufficient.
 */
public class OrderServiceTest {

    private PaymentClient paymentClient;
    private InventoryClient inventoryClient;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        inventoryClient = mock(InventoryClient.class);
        orderService = new OrderService(paymentClient, inventoryClient);
    }

    @Test
    void testCreateOrder_Success() throws ExecutionException, InterruptedException {
        OrderRequest request = new OrderRequest("PROD-001", 5, new BigDecimal("100.00"));
        
        StockCheckResponse stockResponse = new StockCheckResponse("PROD-001", 5, true, 95);
        when(inventoryClient.checkStock("PROD-001", 5)).thenReturn(stockResponse);

        PaymentResponse paymentResponse = new PaymentResponse("APPROVED", "TX-100", new BigDecimal("100.00"));
        when(paymentClient.processPayment(any(PaymentRequest.class))).thenReturn(paymentResponse);

        CompletableFuture<OrderResponse> futureResponse = orderService.createOrderAsync(request, null);
        OrderResponse response = futureResponse.get();

        assertNotNull(response);
        assertEquals("CONFIRMED", response.getStatus());
        assertTrue(response.getMessage().contains("TX-100"));
        
        verify(inventoryClient, times(1)).checkStock("PROD-001", 5);
        verify(paymentClient, times(1)).processPayment(any(PaymentRequest.class));
    }

    @Test
    void testCreateOrder_InsufficientStock() throws ExecutionException, InterruptedException {
        OrderRequest request = new OrderRequest("PROD-002", 10, new BigDecimal("100.00"));
        
        StockCheckResponse stockResponse = new StockCheckResponse("PROD-002", 10, false, 5);
        when(inventoryClient.checkStock("PROD-002", 10)).thenReturn(stockResponse);

        CompletableFuture<OrderResponse> futureResponse = orderService.createOrderAsync(request, null);
        OrderResponse response = futureResponse.get();

        assertNotNull(response);
        assertEquals("REJECTED", response.getStatus());
        assertEquals("Insufficient stock", response.getMessage());
        
        verify(inventoryClient, times(1)).checkStock("PROD-002", 10);
        verify(paymentClient, never()).processPayment(any(PaymentRequest.class));
    }
}
