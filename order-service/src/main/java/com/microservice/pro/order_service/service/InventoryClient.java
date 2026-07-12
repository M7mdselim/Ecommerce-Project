package com.microservice.pro.order_service.service;

import com.microservice.pro.order_service.dto.StockCheckResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * InventoryClient is an OpenFeign client interface targeting the INVENTORY-SERVICE.
 * Service discovery is performed dynamically via Eureka; no hardcoded URLs are used.
 */
@FeignClient(name = "INVENTORY-SERVICE", url = "${inventory-service.url:}")
public interface InventoryClient {

    /**
     * Checks if stock is available for the requested product and quantity.
     * 
     * @param productId the unique ID of the product
     * @param quantity the quantity requested for order creation
     * @return StockCheckResponse containing the availability status
     */
    @GetMapping("/api/v1/inventory/check")
    StockCheckResponse checkStock(
            @RequestParam("productId") String productId,
            @RequestParam("quantity") int quantity
    );
}
