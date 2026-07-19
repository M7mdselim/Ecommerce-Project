package com.microservice.pro.order_service.event;

public record PaymentCompletedEvent(String orderId, String transactionId) {}
