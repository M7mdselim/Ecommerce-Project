package com.microservice.pro.payment_service.event;

public record PaymentCompletedEvent(String orderId, String transactionId) {}
