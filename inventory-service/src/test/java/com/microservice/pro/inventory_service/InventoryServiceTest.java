package com.microservice.pro.inventory_service;

import com.microservice.pro.inventory_service.dto.StockCheckResponse;
import com.microservice.pro.inventory_service.exception.InsufficientStockException;
import com.microservice.pro.inventory_service.exception.ProductNotFoundException;
import com.microservice.pro.inventory_service.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InventoryService validating stock check logic.
 */
public class InventoryServiceTest {

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService();
    }

    @Test
    void testCheckStock_Success() {
        StockCheckResponse response = inventoryService.checkStock("PROD-001", 10);
        assertNotNull(response);
        assertEquals("PROD-001", response.productId());
        assertEquals(10, response.requestedQuantity());
        assertTrue(response.available());
        assertEquals(90, response.remainingStock());
    }

    @Test
    void testCheckStock_InsufficientStock() {
        assertThrows(InsufficientStockException.class, () -> {
            // PROD-002 only has 5 available items, requesting 10 should fail
            inventoryService.checkStock("PROD-002", 10);
        });
    }

    @Test
    void testCheckStock_ProductNotFound() {
        assertThrows(ProductNotFoundException.class, () -> {
            inventoryService.checkStock("PROD-999", 1);
        });
    }
}
