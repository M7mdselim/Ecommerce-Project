package com.microservice.pro.order_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.order_service.entity.Order;
import com.microservice.pro.order_service.entity.OrderStatus;
import com.microservice.pro.order_service.repository.OrderRepository;
import com.microservice.pro.order_service.event.PaymentCompletedEvent;
import com.microservice.pro.order_service.event.PaymentFailedEvent;
import com.microservice.pro.order_service.event.InventoryReleasedEvent;
import com.microservice.pro.order_service.event.InventoryReservationFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class OrderSagaEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(OrderSagaEventHandler.class);
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderSagaEventHandler(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment-events", groupId = "order-service")
    public void handlePaymentEvent(String rawEvent) {
        logger.info("OrderSagaEventHandler: Received payment event: {}", rawEvent);
        try {
            if (rawEvent.contains("PaymentCompletedEvent") || rawEvent.contains("transactionId")) {
                PaymentCompletedEvent event = objectMapper.readValue(rawEvent, PaymentCompletedEvent.class);
                updateOrderStatus(event.orderId(), OrderStatus.CONFIRMED);
                logger.info("OrderSagaEventHandler: Order {} CONFIRMED", event.orderId());
            } else if (rawEvent.contains("PaymentFailedEvent") || rawEvent.contains("reason")) {
                PaymentFailedEvent event = objectMapper.readValue(rawEvent, PaymentFailedEvent.class);
                updateOrderStatus(event.orderId(), OrderStatus.PAYMENT_FAILED);
                logger.warn("OrderSagaEventHandler: Order {} failed payment: {}", event.orderId(), event.reason());
            }
        } catch (Exception ex) {
            logger.error("OrderSagaEventHandler: Error handling payment event: {}", ex.getMessage(), ex);
        }
    }

    @KafkaListener(topics = "inventory-events", groupId = "order-service-cancel")
    public void handleInventoryReleased(String rawEvent) {
        logger.info("OrderSagaEventHandler: Received inventory release event: {}", rawEvent);
        try {
            if (rawEvent.contains("InventoryReleasedEvent") || (!rawEvent.contains("productId") && rawEvent.contains("orderId"))) {
                InventoryReleasedEvent event = objectMapper.readValue(rawEvent, InventoryReleasedEvent.class);
                updateOrderStatus(event.orderId(), OrderStatus.CANCELLED);
                logger.info("OrderSagaEventHandler: Order {} CANCELLED (Inventory released)", event.orderId());
            } else if (rawEvent.contains("InventoryReservationFailedEvent") || rawEvent.contains("reason")) {
                InventoryReservationFailedEvent event = objectMapper.readValue(rawEvent, InventoryReservationFailedEvent.class);
                updateOrderStatus(event.orderId(), OrderStatus.CANCELLED);
                logger.warn("OrderSagaEventHandler: Order {} CANCELLED (Inventory reservation failed)", event.orderId());
            }
        } catch (Exception ex) {
            logger.error("OrderSagaEventHandler: Error handling inventory release event: {}", ex.getMessage(), ex);
        }
    }

    private void updateOrderStatus(String orderId, OrderStatus status) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            orderRepository.save(order);
            logger.info("OrderSagaEventHandler: Updated order {} status to {}", orderId, status);
        });
    }
}
