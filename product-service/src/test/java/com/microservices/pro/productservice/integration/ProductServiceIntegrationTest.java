package com.microservices.pro.productservice.integration;

import com.microservices.pro.productservice.dto.CreateProductRequest;
import com.microservices.pro.productservice.dto.ProductResponse;
import com.microservices.pro.productservice.repository.ProductRepository;
import com.microservices.pro.productservice.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Product service — Task 40.
 *
 * <p>Uses a real PostgreSQL container (Testcontainers) with caching disabled
 * so we can verify cache eviction semantics without spinning up Redis.
 *
 * <p>Tests covered:
 * <ol>
 *   <li>Legacy integration: save and retrieve from a real PostgreSQL container.</li>
 *   <li><strong>Task 40</strong>: Full lifecycle — create → query → update →
 *       verify cache eviction → query again — proving stale data is never served
 *       after a write.</li>
 *   <li>Search-by-name read-side projection (Task 38 integration level).</li>
 * </ol>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProductServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_product_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use simple in-process cache so cache eviction is observable without Redis
        registry.add("spring.cache.type", () -> "simple");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.config.enabled", () -> "false");
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
        // Also wipe the cache so each test starts with a cold cache
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ──────────────────────────────────────────────────────────
    // Legacy test (kept for regression safety)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Legacy: save product to real PostgreSQL and retrieve")
    void saveAndFindProduct_withRealPostgres() {
        CreateProductRequest request = new CreateProductRequest(
                "Gaming Monitor", "4K 144Hz Monitor", new BigDecimal("499.99"), "ELECTRONICS");

        ProductResponse saved = productService.save(request);

        assertThat(saved.id()).isNotNull();

        Optional<ProductResponse> found = productService.findById(saved.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Gaming Monitor");
        assertThat(found.get().price()).isEqualByComparingTo("499.99");
    }

    // ──────────────────────────────────────────────────────────
    // Task 40: create → query → update → cache eviction → query
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Task 40: create → query → update → cache eviction → query returns fresh data")
    void fullLifecycle_createQueryUpdateCacheEvictionQuery() {

        // ── STEP 1: CREATE ──────────────────────────────────────
        CreateProductRequest createRequest = new CreateProductRequest(
                "Laptop Pro", "Original description", new BigDecimal("999.99"), "ELECTRONICS");

        ProductResponse created = productService.save(createRequest);
        Long productId = created.id();

        assertThat(productId).isNotNull();
        assertThat(created.name()).isEqualTo("Laptop Pro");
        assertThat(created.inStock()).isTrue(); // display-oriented field (Task 37)

        // ── STEP 2: QUERY (should populate cache) ───────────────
        Optional<ProductResponse> firstQuery = productService.findById(productId);
        assertThat(firstQuery).isPresent();
        assertThat(firstQuery.get().name()).isEqualTo("Laptop Pro");
        assertThat(firstQuery.get().description()).isEqualTo("Original description");

        // Verify the entry is now in the cache
        var cachedBeforeUpdate = cacheManager.getCache("products").get(productId);
        assertThat(cachedBeforeUpdate).isNotNull();

        // ── STEP 3: UPDATE ──────────────────────────────────────
        CreateProductRequest updateRequest = new CreateProductRequest(
                "Laptop Elite", "Updated description", new BigDecimal("1299.99"), "ELECTRONICS");

        Optional<ProductResponse> updated = productService.update(productId, updateRequest);
        assertThat(updated).isPresent();
        assertThat(updated.get().name()).isEqualTo("Laptop Elite");
        assertThat(updated.get().price()).isEqualByComparingTo("1299.99");

        // ── STEP 4: VERIFY CACHE EVICTION ───────────────────────
        // After update(), @CacheEvict(allEntries=true) should have cleared the cache.
        var cachedAfterUpdate = cacheManager.getCache("products").get(productId);
        assertThat(cachedAfterUpdate)
                .as("Cache entry for product %d must be evicted after update", productId)
                .isNull();

        // ── STEP 5: QUERY AGAIN (must return fresh data, not stale) ─
        Optional<ProductResponse> secondQuery = productService.findById(productId);
        assertThat(secondQuery).isPresent();
        assertThat(secondQuery.get().name())
                .as("Second query must return updated name, not stale cached value")
                .isEqualTo("Laptop Elite");
        assertThat(secondQuery.get().description()).isEqualTo("Updated description");
        assertThat(secondQuery.get().price()).isEqualByComparingTo("1299.99");
    }

    // ──────────────────────────────────────────────────────────
    // Task 38 integration level: search by name
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Task 38 integration: searchByName returns matching products via read-side DTO")
    void searchByName_returnsMatchingProducts() {
        productService.save(new CreateProductRequest(
                "Wireless Keyboard", "Bluetooth keyboard", new BigDecimal("79.99"), "PERIPHERALS"));
        productService.save(new CreateProductRequest(
                "Wireless Mouse", "Bluetooth mouse", new BigDecimal("49.99"), "PERIPHERALS"));
        productService.save(new CreateProductRequest(
                "USB Hub", "7-port USB hub", new BigDecimal("29.99"), "PERIPHERALS"));

        List<ProductResponse> results = productService.searchByName("Wireless");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(ProductResponse::name)
                .containsExactlyInAnyOrder("Wireless Keyboard", "Wireless Mouse");

        // Case-insensitive check
        List<ProductResponse> lowerCase = productService.searchByName("wireless");
        assertThat(lowerCase).hasSize(2);
    }

    @Test
    @DisplayName("Task 38 integration: searchByName returns empty list when no match")
    void searchByName_returnsEmptyList_whenNoMatch() {
        productService.save(new CreateProductRequest(
                "Gaming Chair", "Ergonomic chair", new BigDecimal("299.99"), "FURNITURE"));

        List<ProductResponse> results = productService.searchByName("Laptop");

        assertThat(results).isEmpty();
    }
}
