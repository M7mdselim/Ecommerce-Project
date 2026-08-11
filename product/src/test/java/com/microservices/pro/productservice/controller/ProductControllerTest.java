package com.microservices.pro.productservice.controller;

import com.microservices.pro.productservice.model.Product;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    @DisplayName("GET /api/v1/products should return list of products with 200 OK")
    void getAllProducts_returns200_andList() throws Exception {
        Product p1 = new Product(1L, "Laptop Pro", "High performance laptop", new BigDecimal("1299.99"), "ELECTRONICS");
        Product p2 = new Product(2L, "Wireless Mouse", "Ergonomic wireless mouse", new BigDecimal("49.99"), "ELECTRONICS");

        when(productService.findAll()).thenReturn(List.of(p1, p2));

        mockMvc.perform(get("/api/v1/products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Laptop Pro"))
                .andExpect(jsonPath("$[1].name").value("Wireless Mouse"));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} should return product with 200 OK when found")
    void getProduct_returns200_whenProductExists() throws Exception {
        Product p = new Product(1L, "Laptop Pro", "High performance laptop", new BigDecimal("1299.99"), "ELECTRONICS");

        when(productService.findById(1L)).thenReturn(Optional.of(p));

        mockMvc.perform(get("/api/v1/products/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Pro"))
                .andExpect(jsonPath("$.price").value(1299.99));
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} should return 404 Not Found when product missing")
    void getProduct_returns404_whenNotFound() throws Exception {
        when(productService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/products should create product and return 201 Created")
    void createProduct_returns201_andCreatedProduct() throws Exception {
        Product created = new Product(1L, "Headphones", "Noise canceling", new BigDecimal("199.99"), "ELECTRONICS");

        when(productService.save(any(Product.class))).thenReturn(created);

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
                .andExpect(jsonPath("$.name").value("Headphones"));
    }
}
