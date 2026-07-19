package com.microservice.pro.order_service.event;

public record PaymentFailedEvent(String orderId, String reason) {}
