package com.microservices.pro.productservice.repository;

import com.microservices.pro.productservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
