package com.microservice.pro.payment_service.dto;

import java.math.BigDecimal;

public class PaymentRequest {
    private String orderId;
    private BigDecimal amount;

    public PaymentRequest() {}

    public PaymentRequest(String orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
