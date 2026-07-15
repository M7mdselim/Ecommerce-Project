package com.microservice.pro.order_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.microservice.pro.order_service.dto.OrderRequest;
import com.microservice.pro.order_service.dto.OrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import com.microservice.pro.order_service.messaging.OrderEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
@ActiveProfiles("test")
public class OrderServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    @BeforeEach
    void setUp() {
        WireMock.reset();
        // Stub successful inventory check by default
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\":\"prod123\",\"requestedQuantity\":2,\"available\":true,\"remainingStock\":8}")));
    }

    @Test
    void testPaymentSuccess() throws Exception {
        stubFor(post(urlEqualTo("/api/payments"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"APPROVED\",\"transactionId\":\"tx-12345\",\"amount\":100.00}")));

        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));

        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.message", containsString("tx-12345")));
    }

    @Test
    void testPaymentFailure_RetryAndFallback() throws Exception {
        // Stub to fail all calls
        stubFor(post(urlEqualTo("/api/payments"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));

        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.message", is("Payment unavailable.")));

        // Verify that retry attempted the payment call 3 times
        verify(3, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void testPaymentDelay_TimeoutFallback() throws Exception {
        // Timeout configuration is 2 seconds (2000ms), we simulate a delay of 3000ms
        stubFor(post(urlEqualTo("/api/payments"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"APPROVED\",\"transactionId\":\"tx-timeout\",\"amount\":100.00}")));

        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));
        long startTime = System.currentTimeMillis();

        MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.message", is("Payment timed out.")));

        long duration = System.currentTimeMillis() - startTime;
        // Verify response comes back in about 2 seconds (not 3 seconds)
        assertTrue(duration >= 1800 && duration < 2800, "Expected timeout around 2 seconds but took " + duration + " ms");
    }

    @Test
    void testBulkhead_Exceeded() throws Exception {
        // Bulkhead is 10 concurrent calls. We simulate a delay of 1500ms on payments
        // so that the permits remain occupied during the execution.
        stubFor(post(urlEqualTo("/api/payments"))
                .willReturn(aResponse()
                        .withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"APPROVED\",\"transactionId\":\"tx-bulkhead\",\"amount\":100.00}")));

        OrderRequest request = new OrderRequest("prod123", 2, new BigDecimal("100.00"));
        String requestJson = objectMapper.writeValueAsString(request);

        int totalRequests = 15;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        List<Callable<MvcResult>> tasks = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(request().asyncStarted())
                    .andReturn());
        }

        // Execute all 15 calls concurrently
        List<Future<MvcResult>> futures = executor.invokeAll(tasks);
        List<OrderResponse> responses = new ArrayList<>();

        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            MvcResult asyncResult = mockMvc.perform(asyncDispatch(result)).andReturn();
            String content = asyncResult.getResponse().getContentAsString();
            OrderResponse response = objectMapper.readValue(content, OrderResponse.class);
            responses.add(response);
        }

        executor.shutdown();

        int queuedCount = 0;
        int confirmedOrFailedCount = 0;

        for (OrderResponse r : responses) {
            if ("QUEUED".equals(r.getStatus())) {
                queuedCount++;
                assertEquals("System busy. Your order has been queued.", r.getMessage());
            } else {
                confirmedOrFailedCount++;
            }
        }

        // Out of 15 concurrent calls:
        // Max concurrent is 10. So exactly 10 requests should enter the bulkhead (and eventually succeed or fail)
        // Exactly 5 requests should be rejected immediately (max-wait-duration = 0ms) and return QUEUED status.
        assertEquals(5, queuedCount, "Exactly 5 requests should fail bulkhead");
        assertEquals(10, confirmedOrFailedCount, "Exactly 10 requests should pass bulkhead");
    }
}
