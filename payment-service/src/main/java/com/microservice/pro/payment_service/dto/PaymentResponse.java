package com.microservice.pro.payment_service.dto;

import java.math.BigDecimal;

public class PaymentResponse {
    private String status;
    private String transactionId;
    private BigDecimal amount;

    public PaymentResponse() {}

    public PaymentResponse(String status, String transactionId, BigDecimal amount) {
        this.status = status;
        this.transactionId = transactionId;
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
