package com.microservice.pro.order_service.dto;

/**
 * StockCheckResponse represents the response containing the stock status check result.
 * It is duplicated in Order Service to prevent shared library coupling.
 */
public record StockCheckResponse(
        String productId,
        int requestedQuantity,
        boolean available,
        int remainingStock
) {}
