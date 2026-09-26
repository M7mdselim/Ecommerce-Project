package com.microservice.pro.order_service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.entity.Order;
import com.microservice.pro.order_service.entity.OrderStatus;
import com.microservice.pro.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderSagaOrchestratorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OrderSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        orchestrator = new OrderSagaOrchestrator(orderRepository, kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("Saga Test 1: startSaga should save PENDING order, send ReserveInventoryCommand, and set state to INVENTORY_RESERVING")
    void startSaga_createsOrder_andSendsReserveCommand() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orchestrator.startSaga("PROD-001", 2, new BigDecimal("100.00"));

        assertThat(response.getOrderId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING");

        verify(kafkaTemplate).send(eq("saga-commands"), eq(response.getOrderId()), any(Map.class));
        assertThat(orchestrator.getSagaStates().get(response.getOrderId())).isEqualTo(SagaState.INVENTORY_RESERVING);
    }

    @Test
    @DisplayName("Saga Test 2: Successful Inventory & Payment should confirm order and complete saga")
    void handleInventoryResult_success_transitionsToPaymentProcessing() throws Exception {
        Order order = new Order("ORD-1", "PROD-001", 2, new BigDecimal("100.00"), OrderStatus.PENDING);
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderRepository.findById("ORD-1")).thenReturn(Optional.of(order));

        // Start saga manually
        orchestrator.getSagaStates().put("ORD-1", SagaState.INVENTORY_RESERVING);

        String inventorySuccessPayload = objectMapper.writeValueAsString(Map.of(
                "type", "InventoryResultEvent",
                "orderId", "ORD-1",
                "success", true,
                "reason", "Reserved"
        ));

        orchestrator.handleInventoryResult(inventorySuccessPayload);

        assertThat(orchestrator.getSagaStates().get("ORD-1")).isEqualTo(SagaState.PAYMENT_PROCESSING);
        verify(kafkaTemplate).send(eq("saga-commands"), eq("ORD-1"), any(Map.class));
    }

    @Test
    @DisplayName("Saga Test 3: Payment failure should trigger Compensating ReleaseInventoryCommand")
    void handlePaymentResult_failure_triggersCompensation() throws Exception {
        Order order = new Order("ORD-2", "PROD-001", 2, new BigDecimal("100.00"), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-2")).thenReturn(Optional.of(order));

        orchestrator.getSagaStates().put("ORD-2", SagaState.PAYMENT_PROCESSING);

        String paymentFailedPayload = objectMapper.writeValueAsString(Map.of(
                "type", "PaymentResultEvent",
                "orderId", "ORD-2",
                "success", false,
                "reason", "Insufficient funds"
        ));

        orchestrator.handlePaymentResult(paymentFailedPayload);

        assertThat(orchestrator.getSagaStates().get("ORD-2")).isEqualTo(SagaState.COMPENSATING);

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaTemplate).send(eq("saga-commands"), eq("ORD-2"), captor.capture());
        assertThat(captor.getValue().get("commandType")).isEqualTo("ReleaseInventoryCommand");
    }
}
