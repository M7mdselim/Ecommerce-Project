package com.microservice.pro.payment_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.payment_service.event.InventoryReservedEvent;
import com.microservice.pro.payment_service.event.PaymentCompletedEvent;
import com.microservice.pro.payment_service.event.PaymentFailedEvent;
import com.microservice.pro.payment_service.dto.PaymentRequest;
import com.microservice.pro.payment_service.dto.PaymentResponse;
import com.microservice.pro.payment_service.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentSagaHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentSagaHandler.class);
    private final PaymentService paymentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentSagaHandler(PaymentService paymentService, 
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "inventory-events", groupId = "payment-service")
    public void handleInventoryReserved(String rawEvent) {
        logger.info("PaymentSagaHandler: Received inventory event: {}", rawEvent);
        try {
            if (rawEvent.contains("InventoryReservedEvent") || rawEvent.contains("productId")) {
                InventoryReservedEvent event = objectMapper.readValue(rawEvent, InventoryReservedEvent.class);
                
                logger.info("PaymentSagaHandler: Processing payment for order {}", event.orderId());
                try {
                    // Trigger simulated payment processing
                    PaymentResponse response = paymentService.processPayment(
                            new PaymentRequest(event.orderId(), BigDecimal.ZERO) // dummy amount for simulation
                    );
                    
                    // Publish PaymentCompletedEvent
                    PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                            event.orderId(),
                            response.getTransactionId()
                    );
                    kafkaTemplate.send("payment-events", event.orderId(), completedEvent);
                    logger.info("PaymentSagaHandler: Payment approved for order {}. Published PaymentCompletedEvent.", event.orderId());
                } catch (Exception ex) {
                    logger.warn("PaymentSagaHandler: Payment failed for order {}. Reason: {}", event.orderId(), ex.getMessage());
                    // Publish PaymentFailedEvent
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                            event.orderId(),
                            ex.getMessage()
                    );
                    kafkaTemplate.send("payment-events", event.orderId(), failedEvent);
                }
            }
        } catch (Exception ex) {
            logger.error("PaymentSagaHandler: Error deserializing inventory event", ex);
        }
    }
}
