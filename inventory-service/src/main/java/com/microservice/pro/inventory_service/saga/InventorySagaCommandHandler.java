package com.microservice.pro.inventory_service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.inventory_service.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class InventorySagaCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(InventorySagaCommandHandler.class);

    private final InventoryService inventoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public InventorySagaCommandHandler(InventoryService inventoryService,
                                        KafkaTemplate<String, Object> kafkaTemplate,
                                        ObjectMapper objectMapper) {
        this.inventoryService = inventoryService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "saga-commands", groupId = "inventory-saga-handler-group")
    public void handleCommand(String rawCommand) {
        logger.info("INVENTORY-HANDLER: Received saga command: {}", rawCommand);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmd = objectMapper.readValue(rawCommand, Map.class);
            String commandType = String.valueOf(cmd.getOrDefault("commandType", ""));
            String orderId = (String) cmd.get("orderId");

            if ("ReserveInventoryCommand".equals(commandType)) {
                String productId = (String) cmd.get("productId");
                int quantity = ((Number) cmd.get("quantity")).intValue();
                try {
                    inventoryService.reserveStock(productId, quantity, orderId);
                    Map<String, Object> result = Map.of(
                            "type", "InventoryResultEvent",
                            "orderId", orderId,
                            "success", true,
                            "reason", "Stock reserved"
                    );
                    kafkaTemplate.send("saga-results", orderId, result);
                } catch (Exception e) {
                    logger.error("INVENTORY-HANDLER: Reservation failed for order {}: {}", orderId, e.getMessage());
                    Map<String, Object> result = Map.of(
                            "type", "InventoryResultEvent",
                            "orderId", orderId,
                            "success", false,
                            "reason", e.getMessage()
                    );
                    kafkaTemplate.send("saga-results", orderId, result);
                }
            } else if ("ReleaseInventoryCommand".equals(commandType)) {
                try {
                    inventoryService.releaseStock(orderId);
                    Map<String, Object> result = Map.of(
                            "type", "InventoryReleasedResultEvent",
                            "orderId", orderId,
                            "success", true
                    );
                    kafkaTemplate.send("saga-results", orderId, result);
                } catch (Exception e) {
                    logger.error("INVENTORY-HANDLER: Release failed for order {}: {}", orderId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("INVENTORY-HANDLER: Error processing command: {}", e.getMessage(), e);
        }
    }
}
