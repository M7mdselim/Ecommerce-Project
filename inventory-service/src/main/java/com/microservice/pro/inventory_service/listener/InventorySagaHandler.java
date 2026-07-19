package com.microservice.pro.inventory_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.inventory_service.event.OrderPlacedEvent;
import com.microservice.pro.inventory_service.event.PaymentFailedEvent;
import com.microservice.pro.inventory_service.event.InventoryReservedEvent;
import com.microservice.pro.inventory_service.event.InventoryReservationFailedEvent;
import com.microservice.pro.inventory_service.event.InventoryReleasedEvent;
import com.microservice.pro.inventory_service.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventorySagaHandler {

    private static final Logger logger = LoggerFactory.getLogger(InventorySagaHandler.class);
    private final InventoryService inventoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public InventorySagaHandler(InventoryService inventoryService, 
                                KafkaTemplate<String, Object> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this.inventoryService = inventoryService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void handleOrderPlaced(String rawEvent) {
        logger.info("InventorySagaHandler: Received order event: {}", rawEvent);
        try {
            if (rawEvent.contains("OrderPlacedEvent") || rawEvent.contains("customerId")) {
                OrderPlacedEvent event = objectMapper.readValue(rawEvent, OrderPlacedEvent.class);
                try {
                    // Reserve stock in database/in-memory
                    inventoryService.reserveStock(event.productId(), event.quantity(), event.orderId());
                    
                    // Publish InventoryReservedEvent
                    InventoryReservedEvent reservedEvent = new InventoryReservedEvent(
                            event.orderId(),
                            event.productId(),
                            event.quantity()
                    );
                    kafkaTemplate.send("inventory-events", event.orderId(), reservedEvent);
                    logger.info("InventorySagaHandler: Published InventoryReservedEvent for order {}", event.orderId());
                } catch (Exception ex) {
                    logger.warn("InventorySagaHandler: Stock reservation failed for order {}. Reason: {}", event.orderId(), ex.getMessage());
                    // Publish InventoryReservationFailedEvent
                    InventoryReservationFailedEvent failedEvent = new InventoryReservationFailedEvent(
                            event.orderId(),
                            ex.getMessage()
                    );
                    kafkaTemplate.send("inventory-events", event.orderId(), failedEvent);
                }
            }
        } catch (Exception ex) {
            logger.error("InventorySagaHandler: Error deserializing order event", ex);
        }
    }

    @KafkaListener(topics = "payment-events", groupId = "inventory-compensation")
    public void handlePaymentFailed(String rawEvent) {
        logger.info("InventorySagaHandler: Received payment event for compensation: {}", rawEvent);
        try {
            if (rawEvent.contains("PaymentFailedEvent") || rawEvent.contains("reason")) {
                PaymentFailedEvent event = objectMapper.readValue(rawEvent, PaymentFailedEvent.class);
                
                // Release stock
                inventoryService.releaseStock(event.orderId());
                
                // Publish InventoryReleasedEvent
                InventoryReleasedEvent releasedEvent = new InventoryReleasedEvent(event.orderId());
                kafkaTemplate.send("inventory-events", event.orderId(), releasedEvent);
                logger.info("InventorySagaHandler: Released inventory and published InventoryReleasedEvent for order {}", event.orderId());
            }
        } catch (Exception ex) {
            logger.error("InventorySagaHandler: Error handling compensation", ex);
        }
    }
}
