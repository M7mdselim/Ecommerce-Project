package com.microservices.pro.productservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Write-side command DTO — Session CQRS Lab.
 *
 * <p>Validates incoming data before it reaches the domain layer.
 * Price validation rejects zero and negative values, satisfying Task 39.
 */
public record CreateProductRequest(

        @NotBlank(message = "Product name must not be blank")
        String name,

        String description,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0.00")
        BigDecimal price,

        @NotBlank(message = "Category must not be blank")
        String category
) {}
