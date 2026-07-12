package com.microservice.pro.order_service.exception;

/**
 * InsufficientStockException is thrown when a product exists but the requested quantity
 * exceeds the available stock.
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
