package com.microservice.pro.order_service.config;

import com.microservice.pro.order_service.exception.InsufficientStockException;
import com.microservice.pro.order_service.exception.InventoryUnavailableException;
import com.microservice.pro.order_service.exception.ProductNotFoundException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * InventoryErrorDecoder translates Feign request failures into domain-specific exceptions.
 * Prevents raw FeignExceptions from bubbling up to business layers.
 */
@Component
public class InventoryErrorDecoder implements ErrorDecoder {

    private static final Logger logger = LoggerFactory.getLogger(InventoryErrorDecoder.class);
    private final ErrorDecoder defaultDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        logger.error("[FEIGN ERROR] Method: {}, Status: {}, Reason: {}", methodKey, response.status(), response.reason());

        // Target errors coming from calls made through the InventoryClient
        if (methodKey.contains("InventoryClient")) {
            switch (response.status()) {
                case 404:
                    return new ProductNotFoundException("Product not found in inventory. Status: " + response.status());
                case 409:
                    return new InsufficientStockException("Insufficient stock available in inventory. Status: " + response.status());
                case 503:
                    return new InventoryUnavailableException("Inventory service is currently unavailable. Status: " + response.status());
            }
        }

        // Delegate to the default decoder for other endpoints/statuses
        return defaultDecoder.decode(methodKey, response);
    }
}
