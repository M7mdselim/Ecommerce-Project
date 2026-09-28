package com.microservice.pro.order_service.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import com.microservice.pro.order_service.dto.StockCheckResponse;
import com.microservice.pro.order_service.entity.Order;
import com.microservice.pro.order_service.messaging.OrderEventPublisher;
import com.microservice.pro.order_service.outbox.OutboxEvent;
import com.microservice.pro.order_service.outbox.OutboxEventRepository;
import com.microservice.pro.order_service.repository.OrderRepository;
import com.microservice.pro.order_service.service.InventoryClient;
import com.microservice.pro.order_service.service.OrderService;
import com.microservice.pro.order_service.service.PaymentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests validating the Idempotency Key Pattern (Session 22 / Lab 18 Technical Debt).
 *
 * <p>Failure mode proved resolved:
 * When a network timeout causes a client to retry POST /api/orders with the same Idempotency-Key,
 * the service returns the cached OrderResponse and guarantees:
 * 1. Zero duplicate Order entities in the database.
 * 2. Zero duplicate OutboxEvent records (no duplicate Kafka events or sagas).
 * 3. Zero duplicate inventory stock deductions.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyPatternTest {

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private IdempotentRequestRepository idempotentRequestRepository;

    @Mock
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    private ObjectMapper objectMapper;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orderService = new OrderService(
                paymentClient,
                inventoryClient,
                orderEventPublisher,
                orderRepository,
                outboxEventRepository,
                idempotentRequestRepository,
                objectMapper,
                kafkaTemplate
        );
    }

    @Test
    @DisplayName("Idempotency: First request saves order, outbox event, and idempotency key record")
    void whenFirstRequestWithKey_createsOrderAndPersistsIdempotencyRecord() {
        String idempotencyKey = "key-uuid-111";
        OrderRequest request = new OrderRequest("PROD-001", 2, new BigDecimal("99.99"));

        given(idempotentRequestRepository.findByIdempotencyKey(idempotencyKey))
                .willReturn(Optional.empty());
        given(inventoryClient.checkStock("PROD-001", 2))
                .willReturn(new StockCheckResponse("PROD-001", 2, true, 50));

        OrderResponse response = orderService.createOrder(request, idempotencyKey);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getOrderId()).isNotBlank();

        // 1. Order saved to DB
        verify(orderRepository, times(1)).save(any(Order.class));

        // 2. Outbox event saved to DB
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));

        // 3. IdempotentRequest persisted with matching key and response payload
        ArgumentCaptor<IdempotentRequest> keyCaptor = ArgumentCaptor.forClass(IdempotentRequest.class);
        verify(idempotentRequestRepository, times(1)).save(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(keyCaptor.getValue().getOrderId()).isEqualTo(response.getOrderId());
        assertThat(keyCaptor.getValue().getResponsePayload()).contains(response.getOrderId());
    }

    @Test
    @DisplayName("Idempotency: Duplicate request returns cached response without duplicate DB/Kafka writes")
    void whenDuplicateRequestWithSameKey_returnsCachedResponseImmediately() {
        String idempotencyKey = "key-uuid-222";
        String existingOrderId = "order-already-created-999";
        String cachedJson = "{\"orderId\":\"" + existingOrderId + "\",\"status\":\"PENDING\",\"message\":\"Order placed successfully. Processing payment...\"}";

        IdempotentRequest cachedRecord = new IdempotentRequest(idempotencyKey, existingOrderId, cachedJson);

        given(idempotentRequestRepository.findByIdempotencyKey(idempotencyKey))
                .willReturn(Optional.of(cachedRecord));

        OrderRequest request = new OrderRequest("PROD-001", 2, new BigDecimal("99.99"));

        OrderResponse response = orderService.createOrder(request, idempotencyKey);

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(existingOrderId);
        assertThat(response.getStatus()).isEqualTo("PENDING");

        // Verification of ZERO side effects:
        verify(inventoryClient, never()).checkStock(anyString(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        verify(idempotentRequestRepository, never()).save(any(IdempotentRequest.class));
    }

    @Test
    @DisplayName("Idempotency: Request without key proceeds normally without idempotency record")
    void whenRequestHasNoKey_createsOrderWithoutIdempotencyRecord() {
        OrderRequest request = new OrderRequest("PROD-001", 1, new BigDecimal("49.99"));

        given(inventoryClient.checkStock("PROD-001", 1))
                .willReturn(new StockCheckResponse("PROD-001", 1, true, 10));

        OrderResponse response = orderService.createOrder(request, null);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING");

        verify(orderRepository, times(1)).save(any(Order.class));
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
        verify(idempotentRequestRepository, never()).findByIdempotencyKey(anyString());
        verify(idempotentRequestRepository, never()).save(any(IdempotentRequest.class));
    }
}
