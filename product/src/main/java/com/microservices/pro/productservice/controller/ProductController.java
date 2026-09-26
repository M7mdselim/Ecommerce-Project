package com.microservices.pro.productservice.controller;

import com.microservices.pro.productservice.dto.CreateProductRequest;
import com.microservices.pro.productservice.dto.ProductResponse;
import com.microservices.pro.productservice.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Product REST controller — CQRS Lab update.
 *
 * <p>Command endpoints accept {@link CreateProductRequest} (validated).
 * Query endpoints return {@link ProductResponse} (read-side projection).
 *
 * <p>New endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/products/search?name=…} — name search (Task 38).</li>
 *   <li>{@code PUT /api/v1/products/{id}} — update (needed for Task 40 integration test).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // ──────────────── Query endpoints ────────────────

    @GetMapping
    public List<ProductResponse> getAllProducts() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        return productService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Read-side product search by name (Task 38).
     * Returns all products whose name contains the provided keyword
     * (case-insensitive, partial match).
     *
     * <p>Example: {@code GET /api/v1/products/search?name=laptop}
     */
    @GetMapping("/search")
    public List<ProductResponse> searchByName(@RequestParam String name) {
        return productService.searchByName(name);
    }

    // ──────────────── Command endpoints ────────────────

    /**
     * Creates a new product. The request body is validated; invalid prices
     * (≤ 0) are rejected with 400 Bad Request (Task 39).
     */
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse saved = productService.save(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Updates an existing product and triggers cache eviction (Task 40).
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody CreateProductRequest request) {
        return productService.update(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
