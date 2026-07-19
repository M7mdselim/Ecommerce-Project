package com.microservice.pro.order_service.event;

public record OrderConfirmedEvent(String orderId, String transactionId) {}
