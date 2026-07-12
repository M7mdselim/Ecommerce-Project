package com.microservice.pro.inventory_service.controller;

import com.microservice.pro.inventory_service.dto.StockCheckResponse;
import com.microservice.pro.inventory_service.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * InventoryController exposes endpoints to query stock level statistics.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);
    private final InventoryService inventoryService;

    // Constructor injection of the InventoryService bean
    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Endpoint to check stock availability for a given product and quantity.
     * 
     * @param productId query parameter representing the product
     * @param quantity query parameter representing the quantity requested
     * @return 200 OK with StockCheckResponse, or throws mapped exceptions (404/409) if invalid
     */
    @GetMapping("/check")
    public ResponseEntity<StockCheckResponse> checkStock(
            @RequestParam("productId") String productId,
            @RequestParam("quantity") int quantity) {
        logger.info("REST request to check stock. Product: {}, Quantity: {}", productId, quantity);
        StockCheckResponse response = inventoryService.checkStock(productId, quantity);
        return ResponseEntity.ok(response);
    }
}
