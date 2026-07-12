package com.microservice.pro.order_service.exception;

/**
 * ProductNotFoundException is thrown when a requested product does not exist in the inventory.
 */
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String message) {
        super(message);
    }
}
