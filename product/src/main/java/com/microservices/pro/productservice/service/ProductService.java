package com.microservices.pro.productservice.service;

import com.microservices.pro.productservice.model.Product;
import com.microservices.pro.productservice.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable(value="products", key="'all'")
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Cacheable(value="products", key="#id")
    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    @CacheEvict(value="products", key="#result.id")
    public Product save(Product product) {
        Product saved = productRepository.save(product);
        evictAllProductsCache();
        return saved;
    }

    @CacheEvict(value="products", key="#id")
    public void deleteById(Long id) {
        productRepository.deleteById(id);
        evictAllProductsCache();
    }

    @CacheEvict(value="products", key="'all'")
    public void evictAllProductsCache() {
        // Called internally to invalidate the 'all' products list cache when writes happen
    }
}
