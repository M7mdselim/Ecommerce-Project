package com.microservices.pro.productservice.repository;

import com.microservices.pro.productservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Product repository — Session CQRS Lab update.
 *
 * <p>Added {@link #findByNameContainingIgnoreCase(String)} to support
 * the read-side product search-by-name query (Task 38).
 * Spring Data JPA derives the query automatically from the method name,
 * so no {@code @Query} annotation is needed.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Returns all products whose name contains the given keyword,
     * case-insensitively. Used by the read-side search projection.
     *
     * @param name keyword to search (partial match allowed)
     * @return matching products
     */
    List<Product> findByNameContainingIgnoreCase(String name);
}
