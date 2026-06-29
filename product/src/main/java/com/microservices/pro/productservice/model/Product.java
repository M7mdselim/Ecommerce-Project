package com.microservices.pro.productservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Product domain model — Session 1.
 *
 * Matches the Session 1 Lab 1 spec exactly: id, name, description, price, category.
 * Updated to a standard JPA entity to support automatic database mapping.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;
    private BigDecimal price;
    private String category;

    // Default constructor required by JPA
    public Product() {}

    // Convenience constructor
    public Product(Long id, String name, String description, BigDecimal price, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
    }

    // Dual Getters and Setters (both JavaBean style and Record style for backward compatibility)
    public Long id() { return id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String description() { return description; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal price() { return price; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String category() { return category; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
