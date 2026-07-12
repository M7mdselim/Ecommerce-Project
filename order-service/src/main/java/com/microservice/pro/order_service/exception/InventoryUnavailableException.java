package com.microservice.pro.order_service.exception;

/**
 * InventoryUnavailableException is thrown when the inventory service returns a 503
 * or other server errors indicating it is offline or overloaded.
 */
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message) {
        super(message);
    }
}
