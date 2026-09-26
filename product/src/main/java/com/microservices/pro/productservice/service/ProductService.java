package com.microservices.pro.productservice.service;

import com.microservices.pro.productservice.dto.CreateProductRequest;
import com.microservices.pro.productservice.dto.ProductResponse;
import com.microservices.pro.productservice.model.Product;
import com.microservices.pro.productservice.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Product service — CQRS Lab update.
 *
 * <p>Separates command-side methods (accepting {@link CreateProductRequest})
 * from query-side methods (returning {@link ProductResponse}).
 *
 * <p>New operations added:
 * <ul>
 *   <li>{@link #searchByName(String)} — read-side name search (Task 38).</li>
 *   <li>{@link #update(Long, CreateProductRequest)} — for the integration test
 *       create → query → update → cache-eviction → query flow (Task 40).</li>
 * </ul>
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // ──────────────── Query side ────────────────

    /**
     * Returns all products as read-side projections.
     * Result is cached under key {@code "all"} to avoid repeated DB hits.
     */
    @Cacheable(value = "products", key = "'all'")
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * Returns a single product as a read-side projection, or empty if absent.
     */
    @Cacheable(value = "products", key = "#id")
    public Optional<ProductResponse> findById(Long id) {
        return productRepository.findById(id).map(ProductResponse::from);
    }

    /**
     * Searches products by partial, case-insensitive name match.
     * Read-side projection; result is not cached (volatile search query).
     *
     * @param name keyword to search for
     * @return matching products as read-side DTOs
     */
    public List<ProductResponse> searchByName(String name) {
        return productRepository.findByNameContainingIgnoreCase(name).stream()
                .map(ProductResponse::from)
                .toList();
    }

    // ──────────────── Command side ────────────────

    /**
     * Creates a new product from a validated command request.
     * Evicts the "all" cache so the next read reflects the new product.
     *
     * @param request validated command DTO
     * @return saved product as a read-side projection
     */
    @CacheEvict(value = "products", key = "'all'")
    public ProductResponse save(CreateProductRequest request) {
        Product product = new Product(
                null,
                request.name(),
                request.description(),
                request.price(),
                request.category()
        );
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * Updates an existing product and evicts its individual cache entry
     * as well as the "all" list cache.
     *
     * @param id      product identifier
     * @param request validated command DTO with new values
     * @return updated product as a read-side projection, or empty if not found
     */
    @CacheEvict(value = "products", allEntries = true)
    public Optional<ProductResponse> update(Long id, CreateProductRequest request) {
        return productRepository.findById(id).map(existing -> {
            existing.setName(request.name());
            existing.setDescription(request.description());
            existing.setPrice(request.price());
            existing.setCategory(request.category());
            return ProductResponse.from(productRepository.save(existing));
        });
    }

    /**
     * Deletes a product and evicts all related cache entries.
     */
    @CacheEvict(value = "products", allEntries = true)
    public void deleteById(Long id) {
        productRepository.deleteById(id);
    }
}
