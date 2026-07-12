package com.microservice.pro.order_service;

import com.microservice.pro.order_service.config.InventoryErrorDecoder;
import com.microservice.pro.order_service.exception.InsufficientStockException;
import com.microservice.pro.order_service.exception.InventoryUnavailableException;
import com.microservice.pro.order_service.exception.ProductNotFoundException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InventoryErrorDecoder verifying mapping of HTTP statuses to exceptions.
 */
public class InventoryErrorDecoderTest {

    private final InventoryErrorDecoder errorDecoder = new InventoryErrorDecoder();

    @Test
    void testDecode_NotFound() {
        Response response = Response.builder()
                .status(404)
                .reason("Product Not Found")
                .request(Request.create(Request.HttpMethod.GET, "/api/v1/inventory/check", Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .build();

        Exception exception = errorDecoder.decode("com.microservice.pro.order_service.service.InventoryClient#checkStock(String,int)", response);
        assertTrue(exception instanceof ProductNotFoundException, "Expected ProductNotFoundException, but got: " + exception.getClass().getSimpleName());
    }

    @Test
    void testDecode_Conflict() {
        Response response = Response.builder()
                .status(409)
                .reason("Insufficient Stock")
                .request(Request.create(Request.HttpMethod.GET, "/api/v1/inventory/check", Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .build();

        Exception exception = errorDecoder.decode("com.microservice.pro.order_service.service.InventoryClient#checkStock(String,int)", response);
        assertTrue(exception instanceof InsufficientStockException, "Expected InsufficientStockException, but got: " + exception.getClass().getSimpleName());
    }

    @Test
    void testDecode_Unavailable() {
        Response response = Response.builder()
                .status(503)
                .reason("Service Unavailable")
                .request(Request.create(Request.HttpMethod.GET, "/api/v1/inventory/check", Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .build();

        Exception exception = errorDecoder.decode("com.microservice.pro.order_service.service.InventoryClient#checkStock(String,int)", response);
        assertTrue(exception instanceof InventoryUnavailableException, "Expected InventoryUnavailableException, but got: " + exception.getClass().getSimpleName());
    }
}
