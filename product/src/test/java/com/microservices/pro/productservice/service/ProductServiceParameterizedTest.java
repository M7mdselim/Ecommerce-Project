package com.microservices.pro.productservice.service;

import com.microservices.pro.productservice.dto.ProductResponse;
import com.microservices.pro.productservice.model.Product;
import com.microservices.pro.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Parameterized unit tests for {@link ProductService}.
 *
 * <p>These tests verify query-side behavior (findById returning a
 * {@link ProductResponse} projection) across multiple product IDs.
 * Updated to match the new DTO-based service signatures.
 */
class ProductServiceParameterizedTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        productService = new ProductService(productRepository);
    }

    @ParameterizedTest(name = "findById({0}) shouldExist={2}")
    @CsvSource({
            "1, Laptop Pro, true",
            "2, Wireless Mouse, true",
            "999, N/A, false"
    })
    @DisplayName("findById returns read-side DTO or empty depending on repository response")
    void findById_returnsCorrectReadProjection(Long id, String name, boolean shouldExist) {
        if (shouldExist) {
            Product p = new Product(id, name, "desc", new BigDecimal("99.99"), "ELECTRONICS");
            when(productRepository.findById(id)).thenReturn(Optional.of(p));
        } else {
            when(productRepository.findById(id)).thenReturn(Optional.empty());
        }

        Optional<ProductResponse> result = productService.findById(id);

        assertThat(result.isPresent()).isEqualTo(shouldExist);
        if (shouldExist) {
            assertThat(result.get().name()).isEqualTo(name);
            // Verify the display-oriented inStock field is set (Task 37)
            assertThat(result.get().inStock()).isTrue();
        }
    }
}
