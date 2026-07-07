package com.microservice.pro.order_service.dto;

import java.math.BigDecimal;

public class OrderRequest {
    private String productId;
    private int quantity;
    private BigDecimal amount;

    public OrderRequest() {}

    public OrderRequest(String productId, int quantity, BigDecimal amount) {
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
