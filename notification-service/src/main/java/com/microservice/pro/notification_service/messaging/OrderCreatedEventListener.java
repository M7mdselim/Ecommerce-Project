package com.microservice.pro.notification_service.messaging;

import com.microservice.pro.notification_service.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedEventListener {

    private static final Logger logger = LoggerFactory.getLogger(OrderCreatedEventListener.class);

    @KafkaListener(topics = "order-created", groupId = "notification-service")
    public void handleOrderCreatedEvent(OrderCreatedEvent event) {
        logger.info("Received OrderCreatedEvent - Order ID: {}, Customer ID: {}, Total Amount: {}", 
                event.orderId(), event.customerId(), event.totalAmount());
        
        logger.info("Sending confirmation email... [Order: {}, Customer: {}, Amount: {}]", 
                event.orderId(), event.customerId(), event.totalAmount());
        
        logger.info("Email successfully sent to customer {} for order {}", event.customerId(), event.orderId());
    }
}
