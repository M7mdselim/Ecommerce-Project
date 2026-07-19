package com.microservice.pro.order_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String orderId;

    private String productId;
    private int quantity;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    protected Order() {
        // required by JPA
    }

    public Order(String orderId, String productId, int quantity, BigDecimal amount, OrderStatus status) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.status = status;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }
}
