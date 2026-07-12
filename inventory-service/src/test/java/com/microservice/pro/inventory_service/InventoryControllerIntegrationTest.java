package com.microservice.pro.inventory_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Inventory REST API endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class InventoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testCheckStockEndpoint_Success() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check")
                        .param("productId", "PROD-001")
                        .param("quantity", "5")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("PROD-001"))
                .andExpect(jsonPath("$.requestedQuantity").value(5))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void testCheckStockEndpoint_Conflict() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check")
                        .param("productId", "PROD-002")
                        .param("quantity", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void testCheckStockEndpoint_NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check")
                        .param("productId", "PROD-999")
                        .param("quantity", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
