package com.microservice.pro.order_service.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.LambdaDsl;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "inventory-service")
class OrderServiceInventoryContractTest {

    @Pact(consumer = "order-service", provider = "inventory-service")
    public RequestResponsePact checkStockAvailablePact(PactDslWithProvider builder) {
        return builder
                .given("PROD-001 has 100 units in stock")
                .uponReceiving("a stock check request for PROD-001 with quantity 5")
                .path("/api/v1/inventory/check")
                .method("GET")
                .matchQuery("productId", "PROD-001")
                .matchQuery("quantity", "5")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(LambdaDsl.newJsonBody(body -> {
                    body.stringValue("productId", "PROD-001");
                    body.booleanValue("available", true);
                    body.numberType("remainingStock", 95);
                    body.stringValue("message", "Stock available");
                }).build())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "checkStockAvailablePact")
    @DisplayName("Pact Consumer: verify order-service stock check contract deserialization")
    void checkStock_deserializesAvailableField_correctly(MockServer mockServer) {
        RestTemplate restTemplate = new RestTemplate();
        String url = mockServer.getUrl() + "/api/v1/inventory/check?productId=PROD-001&quantity=5";

        Map<?, ?> response = restTemplate.getForObject(url, Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("productId")).isEqualTo("PROD-001");
        assertThat(response.get("available")).isEqualTo(true);
        assertThat(((Number) response.get("remainingStock")).intValue()).isEqualTo(95);
    }
}
