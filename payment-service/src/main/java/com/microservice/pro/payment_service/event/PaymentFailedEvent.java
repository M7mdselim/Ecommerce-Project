package com.microservice.pro.payment_service.event;

public record PaymentFailedEvent(String orderId, String reason) {}
