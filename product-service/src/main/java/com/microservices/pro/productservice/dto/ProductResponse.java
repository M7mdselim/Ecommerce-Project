package com.microservices.pro.productservice.dto;

import com.microservices.pro.productservice.model.Product;

import java.math.BigDecimal;

/**
 * Read-side projection / DTO — Session CQRS Lab.
 *
 * <p>Separates the read model from the {@link Product} write-side entity.
 * Adds one display-oriented computed field: {@code inStock}, which tells
 * the consumer whether this product is currently available without
 * requiring a round-trip to the inventory service.
 *
 * <p>Justification for the extra field: API consumers (mobile/web clients)
 * need a single boolean to render a "Buy Now" button without calling a
 * second microservice. Embedding it in the read DTO keeps the command model
 * clean while serving the query side cheaply.
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        String category,
        /** Display-oriented field: true when price > 0 (stub — real impl consults Inventory). */
        boolean inStock
) implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Maps a {@link Product} entity to this read projection.
     * The {@code inStock} flag is derived heuristically: a product with a
     * positive price is assumed available. In production this would be
     * enriched from the Inventory service or a dedicated projection store.
     */
    public static ProductResponse from(Product product) {
        boolean inStock = product.getPrice() != null
                && product.getPrice().compareTo(BigDecimal.ZERO) > 0;
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCategory(),
                inStock
        );
    }
}
