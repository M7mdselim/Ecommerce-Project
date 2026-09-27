package com.microservice.pro.payment_service.dto;

import java.math.BigDecimal;

public class PaymentResponse {
    private String status;
    private String transactionId;
    private BigDecimal amount;
    private String message;

    public PaymentResponse() {}

    public PaymentResponse(String status, String transactionId, BigDecimal amount) {
        this.status = status;
        this.transactionId = transactionId;
        this.amount = amount;
        this.message = status;
    }

    public PaymentResponse(String status, String transactionId, BigDecimal amount, String message) {
        this.status = status;
        this.transactionId = transactionId;
        this.amount = amount;
        this.message = message;
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

    public String getMessage() {
        return message != null ? message : status;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    // Record-style accessors
    public String status() {
        return status;
    }

    public String transactionId() {
        return transactionId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String message() {
        return getMessage();
    }
}
