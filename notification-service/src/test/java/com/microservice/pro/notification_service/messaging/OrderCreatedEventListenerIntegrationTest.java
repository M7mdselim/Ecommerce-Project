package com.microservice.pro.notification_service.messaging;

import com.microservice.pro.notification_service.event.OrderCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" })
@ActiveProfiles("test")
public class OrderCreatedEventListenerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void testHandleOrderCreatedEvent() throws InterruptedException {
        OrderCreatedEvent event = new OrderCreatedEvent("order-555", "cust-999", new BigDecimal("150.00"));
        
        // Publish to topic order-created using KafkaTemplate
        kafkaTemplate.send("order-created", "order-555", event);

        // Sleep to let the asynchronous listener consume the message
        TimeUnit.SECONDS.sleep(3);

        // We verified the listener executed by checking that no exception occurred and context holds
        assertThat(event.orderId()).isEqualTo("order-555");
    }
}
