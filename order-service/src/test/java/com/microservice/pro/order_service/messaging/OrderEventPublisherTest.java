package com.microservice.pro.order_service.messaging;

import com.microservice.pro.order_service.event.OrderCreatedEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" })
@ActiveProfiles("test")
public class OrderEventPublisherTest {

    @Autowired
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private Consumer<String, Object> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("testGroup", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.springframework.kafka.support.serializer.JsonDeserializer");
        consumerProps.put("spring.json.trusted.packages", "*");

        ConsumerFactory<String, Object> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        consumer = consumerFactory.createConsumer();
        consumer.subscribe(Collections.singleton("order-created"));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void testPublishOrderCreated() {
        OrderCreatedEvent event = new OrderCreatedEvent("order-999", "cust-123", new BigDecimal("450.00"));
        orderEventPublisher.publishOrderCreated(event);

        ConsumerRecord<String, Object> record = KafkaTestUtils.getSingleRecord(consumer, "order-created", 10000);
        assertNotNull(record);
        assertEquals("order-999", record.key());
        assertNotNull(record.value());
        
        if (record.value() instanceof Map) {
            Map<?, ?> valueMap = (Map<?, ?>) record.value();
            assertEquals("order-999", valueMap.get("orderId"));
            assertEquals("cust-123", valueMap.get("customerId"));
            assertEquals(450.0, ((Number) valueMap.get("totalAmount")).doubleValue(), 0.001);
        } else if (record.value() instanceof OrderCreatedEvent) {
            OrderCreatedEvent received = (OrderCreatedEvent) record.value();
            assertEquals("order-999", received.orderId());
            assertEquals("cust-123", received.customerId());
            assertEquals(new BigDecimal("450.00"), received.totalAmount());
        }
    }
}
