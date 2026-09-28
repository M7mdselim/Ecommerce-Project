package com.microservices.pro.productservice.controller;

import com.microservices.pro.productservice.dto.CreateProductRequest;
import com.microservices.pro.productservice.dto.ProductResponse;
import com.microservices.pro.productservice.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link ProductController}.
 *
 * Task 39: Invalid prices (≤ 0) must be rejected with 400 Bad Request.
 * Task 38: /search?name=… must delegate to the read-side service method.
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    // ──────────────── Query tests ────────────────

    @Test
    @DisplayName("GET /api/v1/products returns 200 with product list including inStock field")
    void getAllProducts_returns200_andList() throws Exception {
        ProductResponse p1 = new ProductResponse(1L, "Laptop Pro", "High performance laptop",
                new BigDecimal("1299.99"), "ELECTRONICS", true);
        ProductResponse p2 = new ProductResponse(2L, "Wireless Mouse", "Ergonomic wireless mouse",
                new BigDecimal("49.99"), "ELECTRONICS", true);

        when(productService.findAll()).thenReturn(List.of(p1, p2));

        mockMvc.perform(get("/api/v1/products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Laptop Pro"))
                .andExpect(jsonPath("$[0].inStock").value(true))
                .andExpect(jsonPath("$[1].name").value("Wireless Mouse"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns 200 with inStock field when product exists")
    void getProduct_returns200_withInStockField() throws Exception {
        ProductResponse p = new ProductResponse(1L, "Laptop Pro", "High performance laptop",
                new BigDecimal("1299.99"), "ELECTRONICS", true);

        when(productService.findById(1L)).thenReturn(Optional.of(p));

        mockMvc.perform(get("/api/v1/products/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Pro"))
                .andExpect(jsonPath("$.price").value(1299.99))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} returns 404 when product missing")
    void getProduct_returns404_whenNotFound() throws Exception {
        when(productService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/products/search?name=… returns matching products (Task 38)")
    void searchByName_returnMatchingProducts() throws Exception {
        ProductResponse p = new ProductResponse(1L, "Laptop Pro", "High performance laptop",
                new BigDecimal("1299.99"), "ELECTRONICS", true);

        when(productService.searchByName("Laptop")).thenReturn(List.of(p));

        mockMvc.perform(get("/api/v1/products/search")
                        .param("name", "Laptop")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Laptop Pro"));
    }

    // ──────────────── Command tests ────────────────

    @Test
    @DisplayName("POST /api/v1/products with valid body returns 201 Created")
    void createProduct_returns201_andCreatedProduct() throws Exception {
        ProductResponse created = new ProductResponse(1L, "Headphones", "Noise canceling",
                new BigDecimal("199.99"), "ELECTRONICS", true);

        when(productService.save(any(CreateProductRequest.class))).thenReturn(created);

        String jsonPayload = """
                {
                    "name": "Headphones",
                    "description": "Noise canceling",
                    "price": 199.99,
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Headphones"))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/products with negative price returns 400 Bad Request (Task 39)")
    void createProduct_returns400_whenPriceIsNegative() throws Exception {
        String invalidPayload = """
                {
                    "name": "Bad Product",
                    "description": "Has invalid price",
                    "price": -5.00,
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/products with price=0 returns 400 Bad Request (Task 39)")
    void createProduct_returns400_whenPriceIsZero() throws Exception {
        String invalidPayload = """
                {
                    "name": "Free Product",
                    "description": "Price is zero",
                    "price": 0,
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/products with missing price returns 400 Bad Request (Task 39)")
    void createProduct_returns400_whenPriceIsMissing() throws Exception {
        String invalidPayload = """
                {
                    "name": "No Price",
                    "description": "Missing price field",
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/products with blank name returns 400 Bad Request (Task 39 boundary)")
    void createProduct_returns400_whenNameIsBlank() throws Exception {
        String invalidPayload = """
                {
                    "name": "",
                    "description": "No name",
                    "price": 19.99,
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/products/{id} returns 200 when product updated")
    void updateProduct_returns200_whenProductExists() throws Exception {
        ProductResponse updated = new ProductResponse(1L, "Laptop Elite", "Updated laptop",
                new BigDecimal("1499.99"), "ELECTRONICS", true);

        when(productService.update(eq(1L), any(CreateProductRequest.class)))
                .thenReturn(Optional.of(updated));

        String jsonPayload = """
                {
                    "name": "Laptop Elite",
                    "description": "Updated laptop",
                    "price": 1499.99,
                    "category": "ELECTRONICS"
                }
                """;

        mockMvc.perform(put("/api/v1/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Elite"))
                .andExpect(jsonPath("$.price").value(1499.99));
    }
}
