package com.microservice.pro.inventory_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * InsufficientStockException is thrown when a check requests more quantity than is available.
 * Resolves to HTTP 409 Conflict.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
