package com.microservice.pro.order_service.messaging;

import com.microservice.pro.order_service.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventPublisher.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        logger.info("Publishing OrderCreatedEvent to topic 'order-created': {}", event);
        kafkaTemplate.send("order-created", event.orderId(), event);
        logger.info("Published OrderCreatedEvent. Order ID: {}, Customer ID: {}, Total Amount: {}", 
                event.orderId(), event.customerId(), event.totalAmount());
    }
}
