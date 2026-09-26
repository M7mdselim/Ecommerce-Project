package com.microservice.pro.order_service.wiremock;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWireMock(port = 0)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:wiremock_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class OrderServicePaymentWireMockTest {

    @org.springframework.beans.factory.annotation.Value("${wiremock.server.port}")
    private int wireMockPort;

    @Autowired
    private RestTemplate restTemplate;

    @Test
    @DisplayName("WireMock Stubbing: simulate successful Payment Service response")
    void testPaymentSuccess_viaWireMock() {
        stubFor(post(urlEqualTo("/api/v1/payments"))
                .withHeader("Content-Type", containing("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "paymentId": "PAY-999111",
                                    "orderId": "ORD-1001",
                                    "status": "SUCCESS",
                                    "transactionId": "TXN-888777"
                                }
                                """)));

        String wireMockUrl = "http://localhost:" + wireMockPort + "/api/v1/payments";
        String payload = "{\"orderId\":\"ORD-1001\",\"amount\":99.99}";

        ResponseEntity<String> response = restTemplate.postForEntity(wireMockUrl, payload, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("PAY-999111");
        assertThat(response.getBody()).contains("SUCCESS");
    }

    @Test
    @DisplayName("WireMock Stubbing: simulate payment service failure (500 Internal Error)")
    void testPaymentFailure_viaWireMock() {
        stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Payment Gateway Unavailable\"}")));

        String wireMockUrl = "http://localhost:" + wireMockPort + "/api/v1/payments";
        String payload = "{\"orderId\":\"ORD-1002\",\"amount\":150.00}";

        try {
            restTemplate.postForEntity(wireMockUrl, payload, String.class);
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("500");
        }
    }
}
