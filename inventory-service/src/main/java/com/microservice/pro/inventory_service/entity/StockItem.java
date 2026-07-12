package com.microservice.pro.inventory_service.entity;

/**
 * StockItem is an immutable data carrier record representing stock statistics.
 */
public record StockItem(
        String productId,
        int availableQuantity,
        int reservedQuantity
) {
    /**
     * Verifies if requested quantity can be served.
     * 
     * @param requested quantity
     * @return true if available capacity can accommodate the request
     */
    public boolean hasStock(int requested) {
        return (availableQuantity - reservedQuantity) >= requested;
    }
}
