package com.microservice.pro.inventory_service.dto;

/**
 * StockCheckResponse defines the response structure for inventory stock queries.
 */
public record StockCheckResponse(
        String productId,
        int requestedQuantity,
        boolean available,
        int remainingStock
) {}
