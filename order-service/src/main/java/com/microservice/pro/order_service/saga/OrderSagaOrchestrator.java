package com.microservice.pro.order_service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.entity.Order;
import com.microservice.pro.order_service.entity.OrderStatus;
import com.microservice.pro.order_service.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderSagaOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, SagaState> sagaStates = new ConcurrentHashMap<>();

    public OrderSagaOrchestrator(OrderRepository orderRepository,
                                 KafkaTemplate<String, Object> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, SagaState> getSagaStates() {
        return sagaStates;
    }

    public OrderResponse startSaga(String productId, int quantity, BigDecimal amount) {
        String orderId = UUID.randomUUID().toString();
        logger.info("ORCHESTRATOR: Starting Saga for OrderId: {}, ProductId: {}, Quantity: {}, Amount: {}",
                orderId, productId, quantity, amount);

        Order order = new Order(orderId, productId, quantity, amount, OrderStatus.PENDING);
        orderRepository.save(order);
        sagaStates.put(orderId, SagaState.STARTED);

        Map<String, Object> cmd = Map.of(
                "commandType", "ReserveInventoryCommand",
                "orderId", orderId,
                "productId", productId,
                "quantity", quantity
        );

        kafkaTemplate.send("saga-commands", orderId, cmd);
        sagaStates.put(orderId, SagaState.INVENTORY_RESERVING);

        return new OrderResponse(orderId, "PENDING", "Saga started - reserving inventory");
    }

    @KafkaListener(topics = "saga-results", groupId = "orchestrator-inventory-group")
    public void handleInventoryResult(String rawResult) {
        logger.info("ORCHESTRATOR: Received inventory saga result: {}", rawResult);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(rawResult, Map.class);
            String type = String.valueOf(result.getOrDefault("type", ""));
            String orderId = (String) result.get("orderId");
            Boolean success = (Boolean) result.get("success");

            if (!"InventoryResultEvent".equals(type) || orderId == null) {
                return;
            }

            if (sagaStates.get(orderId) != SagaState.INVENTORY_RESERVING) {
                logger.warn("ORCHESTRATOR: Unexpected inventory result state for OrderId: {}", orderId);
                return;
            }

            if (Boolean.TRUE.equals(success)) {
                sagaStates.put(orderId, SagaState.INVENTORY_RESERVED);
                Order order = orderRepository.findById(orderId).orElseThrow();

                Map<String, Object> cmd = Map.of(
                        "commandType", "ProcessPaymentCommand",
                        "orderId", orderId,
                        "amount", order.getAmount()
                );

                kafkaTemplate.send("saga-commands", orderId, cmd);
                sagaStates.put(orderId, SagaState.PAYMENT_PROCESSING);
            } else {
                sagaStates.put(orderId, SagaState.INVENTORY_FAILED);
                updateOrderStatus(orderId, OrderStatus.CANCELLED);
                sagaStates.remove(orderId);
            }
        } catch (Exception e) {
            logger.error("ORCHESTRATOR: Error parsing inventory result: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "saga-results", groupId = "orchestrator-payment-group")
    public void handlePaymentResult(String rawResult) {
        logger.info("ORCHESTRATOR: Received payment saga result: {}", rawResult);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(rawResult, Map.class);
            String type = String.valueOf(result.getOrDefault("type", ""));
            String orderId = (String) result.get("orderId");
            Boolean success = (Boolean) result.get("success");

            if (!"PaymentResultEvent".equals(type) || orderId == null) {
                return;
            }

            if (sagaStates.get(orderId) != SagaState.PAYMENT_PROCESSING) {
                return;
            }

            if (Boolean.TRUE.equals(success)) {
                sagaStates.put(orderId, SagaState.COMPLETED);
                updateOrderStatus(orderId, OrderStatus.CONFIRMED);
                sagaStates.remove(orderId);
                logger.info("ORCHESTRATOR: Saga COMPLETED successfully for OrderId: {}", orderId);
            } else {
                sagaStates.put(orderId, SagaState.PAYMENT_FAILED);
                updateOrderStatus(orderId, OrderStatus.PAYMENT_FAILED);

                // Issue Compensating Command to release reserved inventory
                Map<String, Object> cmd = Map.of(
                        "commandType", "ReleaseInventoryCommand",
                        "orderId", orderId
                );

                kafkaTemplate.send("saga-commands", orderId, cmd);
                sagaStates.put(orderId, SagaState.COMPENSATING);
            }
        } catch (Exception e) {
            logger.error("ORCHESTRATOR: Error parsing payment result: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "saga-results", groupId = "orchestrator-release-group")
    public void handleInventoryReleased(String rawResult) {
        logger.info("ORCHESTRATOR: Received inventory release result: {}", rawResult);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(rawResult, Map.class);
            String type = String.valueOf(result.getOrDefault("type", ""));
            String orderId = (String) result.get("orderId");

            if (!"InventoryReleasedResultEvent".equals(type) || orderId == null) {
                return;
            }

            if (sagaStates.get(orderId) == SagaState.COMPENSATING) {
                sagaStates.put(orderId, SagaState.CANCELLED);
                updateOrderStatus(orderId, OrderStatus.CANCELLED);
                sagaStates.remove(orderId);
                logger.info("ORCHESTRATOR: Compensation complete for OrderId: {}", orderId);
            }
        } catch (Exception e) {
            logger.error("ORCHESTRATOR: Error parsing release result: {}", e.getMessage(), e);
        }
    }

    private void updateOrderStatus(String orderId, OrderStatus status) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            orderRepository.save(order);
            logger.info("ORCHESTRATOR: Updated OrderId: {} status to {}", orderId, status);
        });
    }
}
