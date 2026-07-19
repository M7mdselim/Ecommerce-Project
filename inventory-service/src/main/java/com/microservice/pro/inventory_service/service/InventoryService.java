package com.microservice.pro.inventory_service.service;

import com.microservice.pro.inventory_service.dto.StockCheckResponse;
import com.microservice.pro.inventory_service.entity.StockItem;
import com.microservice.pro.inventory_service.exception.InsufficientStockException;
import com.microservice.pro.inventory_service.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InventoryService handles queries on product stock status.
 * State is managed using a thread-safe ConcurrentHashMap in-memory database.
 */
@Service
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);

    // ConcurrentHashMap database storing product stock items
    private final Map<String, StockItem> inventory = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservationsByOrderId = new ConcurrentHashMap<>();

    public record Reservation(String productId, int quantity) {}

    public InventoryService() {
        // Pre-populate data exactly as specified in the requirements
        inventory.put("PROD-001", new StockItem("PROD-001", 100, 0));
        inventory.put("PROD-002", new StockItem("PROD-002", 5, 0));
        inventory.put("PROD-003", new StockItem("PROD-003", 0, 0));
        logger.info("InventoryService initialized with pre-populated records.");
    }

    /**
     * Checks if stock is available for the requested product and quantity.
     * 
     * @param productId unique ID of the product
     * @param quantity quantity requested
     * @return StockCheckResponse containing the result
     */
    public StockCheckResponse checkStock(String productId, int quantity) {
        logger.info("Stock check request for Product: {}, Quantity: {}", productId, quantity);

        StockItem item = inventory.get(productId);
        if (item == null) {
            logger.error("Stock check failure: Product: {} not found", productId);
            throw new ProductNotFoundException("Product not found in inventory: " + productId);
        }

        boolean hasStock = item.hasStock(quantity);
        int remainingStock = item.availableQuantity() - item.reservedQuantity() - quantity;

        logger.info("Stock status - Product: {}, Available: {}, Reserved: {}, Requested: {}, Remaining: {}", 
                productId, item.availableQuantity(), item.reservedQuantity(), quantity, remainingStock + quantity);

        if (!hasStock) {
            logger.warn("Stock check failure: Insufficient stock for Product: {}", productId);
            throw new InsufficientStockException("Insufficient stock for product " + productId + ". Requested: " + quantity);
        }

        logger.info("Stock check success: Product {} is available", productId);
        return new StockCheckResponse(productId, quantity, true, remainingStock);
    }

    /**
     * Reserves stock for a product and maps it to an order ID.
     */
    public void reserveStock(String productId, int quantity, String orderId) {
        logger.info("SAGA: Reserving stock for Product: {}, Quantity: {}, Order: {}", productId, quantity, orderId);
        inventory.compute(productId, (key, item) -> {
            if (item == null) {
                throw new ProductNotFoundException("Product not found in inventory: " + productId);
            }
            if (!item.hasStock(quantity)) {
                throw new InsufficientStockException("Insufficient stock for product " + productId + ". Requested: " + quantity);
            }
            StockItem updated = new StockItem(productId, item.availableQuantity(), item.reservedQuantity() + quantity);
            reservationsByOrderId.put(orderId, new Reservation(productId, quantity));
            logger.info("SAGA: Reserved successfully. New state: {}", updated);
            return updated;
        });
    }

    /**
     * Releases reserved stock for a completed compensation flow.
     */
    public void releaseStock(String orderId) {
        logger.info("SAGA: Releasing stock for Order: {}", orderId);
        Reservation reservation = reservationsByOrderId.remove(orderId);
        if (reservation != null) {
            String productId = reservation.productId();
            int quantity = reservation.quantity();
            inventory.compute(productId, (key, item) -> {
                if (item == null) {
                    return null;
                }
                int newReserved = Math.max(0, item.reservedQuantity() - quantity);
                StockItem updated = new StockItem(productId, item.availableQuantity(), newReserved);
                logger.info("SAGA: Released successfully. New state: {}", updated);
                return updated;
            });
        } else {
            logger.info("SAGA: No reservation found for Order: {}, ignoring compensation (idempotent)", orderId);
        }
    }
}
